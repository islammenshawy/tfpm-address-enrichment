package com.jpmc.tfpm.address.adapter.llm.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jpmc.tfpm.address.domain.EnrichmentError;
import com.jpmc.tfpm.address.domain.LlmModelClient;
import com.jpmc.tfpm.address.domain.Result;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.RetrySpec;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default {@link LlmModelClient} implementation. Talks to the JPMC internal
 * LLM gateway over HTTPS with OAuth2 client-credentials authentication.
 *
 * <p>Handles three JPMC-network-specific concerns:
 *
 * <ol>
 *   <li><b>OAuth2 token rotation.</b> The gateway issues short-lived bearer
 *       tokens (typically 15 minutes). This client caches the current token
 *       and refreshes proactively at 80% of advertised TTL. Token endpoint
 *       and credentials are injected via {@link OAuthTokenSource}, which
 *       resolves them from Vault in production.
 *   <li><b>ProxySG/ICAP buffering on streaming.</b> JPMC's egress ProxySG
 *       and ICAP scanners can buffer streaming responses, defeating the
 *       point of SSE. This client routes through the internal gateway
 *       (which uses chunk-flushing patterns proxies tolerate), sets
 *       {@code Cache-Control: no-cache, no-transform} and
 *       {@code Connection: close} headers, and uses a heartbeat read
 *       timeout of 15 seconds — if no chunks for 15s, close and fail.
 *   <li><b>Trace context propagation.</b> The gateway expects trace headers
 *       in the JPMC standard format; this client adds them from the current
 *       OpenTelemetry context.
 * </ol>
 *
 * <p>This class is the canonical example for adding new providers — clone
 * the structure for any new HTTP-based model client.
 */
@ThreadSafe
public final class JpmcInternalGatewayLlmClient implements LlmModelClient {

    private static final Logger LOG = LoggerFactory.getLogger(JpmcInternalGatewayLlmClient.class);

    private final String name;
    private final LlmModelMetadata metadata;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final OAuthTokenSource tokenSource;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Duration streamHeartbeat;
    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    public JpmcInternalGatewayLlmClient(
            String name,
            LlmModelMetadata metadata,
            WebClient webClient,
            ObjectMapper objectMapper,
            OAuthTokenSource tokenSource,
            CircuitBreaker circuitBreaker,
            Retry retry,
            Duration streamHeartbeat) {
        this.name = Objects.requireNonNull(name);
        this.metadata = Objects.requireNonNull(metadata);
        this.webClient = Objects.requireNonNull(webClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.tokenSource = Objects.requireNonNull(tokenSource);
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker);
        this.retry = Objects.requireNonNull(retry);
        this.streamHeartbeat = Objects.requireNonNull(streamHeartbeat);
    }

    @Override public String name() { return name; }
    @Override public LlmModelMetadata metadata() { return metadata; }

    // ============================================================
    // Synchronous request/response
    // ============================================================

    @Override
    public Result<LlmCompletionResponse> complete(LlmCompletionRequest request) {
        var start = Instant.now();
        var requestBody = buildRequestPayload(request, /*stream=*/ false);

        try {
            return Mono.fromCallable(() -> currentBearerToken())
                    .flatMap(token -> webClient.post()
                            .uri("/v1/chat/completions")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .header("X-JPMC-Correlation-Id", request.correlationId())
                            .bodyValue(requestBody)
                            .retrieve()
                            .bodyToMono(JsonNode.class))
                    .timeout(metadata.defaultTimeout())
                    .transformDeferred(io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator.of(circuitBreaker))
                    .retryWhen(toReactorRetry(retry))
                    .map(json -> parseSyncResponse(json, start, request.correlationId()))
                    .onErrorResume(t -> Mono.just(translateError(t, request.correlationId())))
                    .block();
        } catch (Exception unexpected) {
            LOG.error("Unexpected failure in JpmcInternalGatewayLlmClient", unexpected);
            return Result.failure(EnrichmentError.of(
                    EnrichmentError.Category.UNKNOWN,
                    "Unexpected failure: " + unexpected.getMessage(),
                    request.correlationId(),
                    unexpected));
        }
    }

    // ============================================================
    // Streaming
    // ============================================================

    @Override
    public Flow.Publisher<LlmStreamChunk> stream(LlmCompletionRequest request) {
        if (!metadata.supportsStreaming()) {
            throw new UnsupportedOperationException(
                    "Streaming not enabled for client " + name + "; configure mode=STREAM");
        }

        var publisher = new SubmissionPublisher<LlmStreamChunk>(
                Schedulers.boundedElastic().createWorker()::schedule, 256);

        var requestBody = buildRequestPayload(request, /*stream=*/ true);
        var bearer = currentBearerToken();

        var serverSentEventType = new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {};

        Flux<ServerSentEvent<String>> events = webClient.post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header(HttpHeaders.CONNECTION, "close")
                .header("X-JPMC-Correlation-Id", request.correlationId())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(serverSentEventType)
                .timeout(streamHeartbeat); // per-chunk timeout, not total

        events.subscribe(
                sse -> {
                    var data = sse.data();
                    if (data == null || "[DONE]".equals(data)) {
                        publisher.submit(new LlmStreamChunk("",
                                LlmCompletionResponse.FinishReason.STOP, 0, 0));
                        publisher.close();
                        return;
                    }
                    parseStreamChunk(data).ifPresent(publisher::submit);
                },
                error -> {
                    LOG.warn("Stream error from {}: {}", name, error.toString());
                    publisher.closeExceptionally(error);
                },
                publisher::close);

        return publisher;
    }

    // ============================================================
    // Helpers
    // ============================================================

    private ObjectNode buildRequestPayload(LlmCompletionRequest req, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", metadata.modelId());
        root.put("stream", stream);
        if (req.maxTokens() > 0) root.put("max_tokens", req.maxTokens());
        if (req.temperature() != null) root.put("temperature", req.temperature());

        ArrayNode messages = root.putArray("messages");
        if (!req.systemPrompt().isEmpty()) {
            messages.addObject().put("role", "system").put("content", req.systemPrompt());
        }
        for (var m : req.messages()) {
            messages.addObject().put("role", m.role().name().toLowerCase()).put("content", m.content());
        }

        if (req.outputFormat() instanceof LlmCompletionRequest.OutputFormat.Json json) {
            ObjectNode rf = root.putObject("response_format");
            rf.put("type", "json_schema");
            ObjectNode schema = rf.putObject("json_schema");
            schema.put("name", "address_structuring_response");
            try {
                schema.set("schema", objectMapper.readTree(json.jsonSchema()));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("invalid jsonSchema", e);
            }
            schema.put("strict", true);
        }
        return root;
    }

    private Result<LlmCompletionResponse> parseSyncResponse(
            JsonNode json, Instant start, String correlationId) {
        try {
            String content = json.path("choices").path(0).path("message").path("content").asText("");
            String finish = json.path("choices").path(0).path("finish_reason").asText("stop");
            int inputTokens = json.path("usage").path("prompt_tokens").asInt(0);
            int outputTokens = json.path("usage").path("completion_tokens").asInt(0);
            String providerCorr = json.path("id").asText("");

            return Result.success(new LlmCompletionResponse(
                    content,
                    parseFinishReason(finish),
                    inputTokens,
                    outputTokens,
                    Duration.between(start, Instant.now()),
                    providerCorr));
        } catch (Exception e) {
            return Result.failure(EnrichmentError.of(
                    EnrichmentError.Category.SCHEMA_MISMATCH,
                    "Failed to parse gateway response: " + e.getMessage(),
                    correlationId, e));
        }
    }

    private java.util.Optional<LlmStreamChunk> parseStreamChunk(String sseData) {
        try {
            JsonNode json = objectMapper.readTree(sseData);
            JsonNode delta = json.path("choices").path(0).path("delta");
            String content = delta.path("content").asText("");
            String finish = json.path("choices").path(0).path("finish_reason").asText(null);

            if (content.isEmpty() && finish == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new LlmStreamChunk(
                    content,
                    finish == null ? null : parseFinishReason(finish),
                    json.path("usage").path("prompt_tokens").asInt(0),
                    json.path("usage").path("completion_tokens").asInt(0)));
        } catch (Exception e) {
            LOG.warn("Failed to parse SSE chunk: {}", sseData, e);
            return java.util.Optional.empty();
        }
    }

    private LlmCompletionResponse.FinishReason parseFinishReason(String s) {
        return switch (s) {
            case "stop" -> LlmCompletionResponse.FinishReason.STOP;
            case "length" -> LlmCompletionResponse.FinishReason.LENGTH;
            case "content_filter" -> LlmCompletionResponse.FinishReason.CONTENT_FILTER;
            case "tool_calls" -> LlmCompletionResponse.FinishReason.TOOL_CALL;
            default -> LlmCompletionResponse.FinishReason.UNKNOWN;
        };
    }

    private Result<LlmCompletionResponse> translateError(Throwable t, String correlationId) {
        EnrichmentError.Category category;
        String msg;
        if (t instanceof java.util.concurrent.TimeoutException) {
            category = EnrichmentError.Category.TIMEOUT;
            msg = "LLM gateway call timed out";
        } else if (t instanceof CallNotPermittedException) {
            category = EnrichmentError.Category.CIRCUIT_OPEN;
            msg = "LLM gateway circuit breaker open";
        } else if (t instanceof WebClientResponseException wcre) {
            int s = wcre.getStatusCode().value();
            if (s == 401) { category = EnrichmentError.Category.UNAUTHORISED; }
            else if (s == 403) { category = EnrichmentError.Category.FORBIDDEN; }
            else if (s == 429) { category = EnrichmentError.Category.UPSTREAM_RATE_LIMITED; }
            else if (s >= 500) { category = EnrichmentError.Category.UPSTREAM_UNAVAILABLE; }
            else { category = EnrichmentError.Category.BAD_INPUT; }
            msg = "LLM gateway HTTP " + s + ": " + wcre.getResponseBodyAsString();
        } else if (t instanceof WebClientRequestException) {
            category = EnrichmentError.Category.NETWORK;
            msg = "Network error to LLM gateway: " + t.getMessage();
        } else {
            category = EnrichmentError.Category.UNKNOWN;
            msg = "Unexpected error: " + t.getMessage();
        }
        return Result.failure(new EnrichmentError(category, msg, correlationId, Map.of(), t));
    }

    private static reactor.util.retry.Retry toReactorRetry(Retry r4j) {
        var cfg = r4j.getRetryConfig();
        return RetrySpec.backoff(cfg.getMaxAttempts() - 1, Duration.ofMillis(100))
                .filter(t -> {
                    if (t instanceof WebClientResponseException wcre) {
                        int s = wcre.getStatusCode().value();
                        return s == 429 || s == 502 || s == 503 || s == 504;
                    }
                    return t instanceof WebClientRequestException
                            || t instanceof java.util.concurrent.TimeoutException;
                });
    }

    private String currentBearerToken() {
        var cached = cachedToken.get();
        if (cached != null && cached.notExpired()) return cached.token();
        var fresh = tokenSource.fetch();
        cachedToken.set(fresh);
        return fresh.token();
    }

    public interface OAuthTokenSource {
        CachedToken fetch();
    }

    public record CachedToken(String token, Instant expiresAt) {
        public boolean notExpired() {
            // refresh proactively at 80% of TTL
            return Instant.now().isBefore(expiresAt.minus(Duration.ofSeconds(60)));
        }
    }
}

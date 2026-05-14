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

/**
 * {@link LlmModelClient} for any endpoint that speaks the OpenAI
 * chat-completions API: OpenAI itself, Anthropic via OpenAI-compat shim,
 * Together.ai, vLLM, Ollama, LocalAI, etc.
 *
 * <p>Used in three contexts:
 *
 * <ul>
 *   <li>Local development against a desktop Ollama instance.
 *   <li>Vendor evaluation work where comparing models requires switching
 *       endpoints.
 *   <li>Future fallback if the JPMC internal gateway is unavailable
 *       and an alternative is approved.
 * </ul>
 *
 * <p>Differences from {@link JpmcInternalGatewayLlmClient}:
 *
 * <ul>
 *   <li>Bearer token is static (from config or Vault) — no OAuth flow.
 *   <li>No middlebox-defeating headers — assumes direct or simple-proxy network.
 *   <li>Otherwise identical wire format.
 * </ul>
 */
@ThreadSafe
public final class OpenAiCompatibleLlmClient implements LlmModelClient {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final String name;
    private final LlmModelMetadata metadata;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String bearerToken;
    private final CircuitBreaker circuitBreaker;
    private final int maxAttempts;

    public OpenAiCompatibleLlmClient(
            String name,
            LlmModelMetadata metadata,
            WebClient webClient,
            ObjectMapper objectMapper,
            String bearerToken,
            CircuitBreaker circuitBreaker,
            int maxAttempts) {
        this.name = Objects.requireNonNull(name);
        this.metadata = Objects.requireNonNull(metadata);
        this.webClient = Objects.requireNonNull(webClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.bearerToken = Objects.requireNonNull(bearerToken);
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker);
        this.maxAttempts = maxAttempts;
    }

    @Override public String name() { return name; }
    @Override public LlmModelMetadata metadata() { return metadata; }

    @Override
    public Result<LlmCompletionResponse> complete(LlmCompletionRequest request) {
        var start = Instant.now();
        var requestBody = buildRequestPayload(request, /*stream=*/ false);

        try {
            var req = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            // Only add Bearer auth when token is non-empty (Azure uses api-key header instead)
            if (bearerToken != null && !bearerToken.isEmpty()) {
                req = req.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
            }
            return req
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(metadata.defaultTimeout())
                    .transformDeferred(io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator.of(circuitBreaker))
                    .retryWhen(RetrySpec.backoff(maxAttempts - 1L, Duration.ofMillis(100))
                            .filter(t -> isRetryable(t)))
                    .map(json -> parseSyncResponse(json, start, request.correlationId()))
                    .onErrorResume(t -> Mono.just(translateError(t, request.correlationId())))
                    .block();
        } catch (Exception unexpected) {
            LOG.error("Unexpected failure in OpenAiCompatibleLlmClient", unexpected);
            return Result.failure(EnrichmentError.of(
                    EnrichmentError.Category.UNKNOWN,
                    "Unexpected failure: " + unexpected.getMessage(),
                    request.correlationId(),
                    unexpected));
        }
    }

    @Override
    public Flow.Publisher<LlmStreamChunk> stream(LlmCompletionRequest request) {
        if (!metadata.supportsStreaming()) {
            throw new UnsupportedOperationException(
                    "Streaming not enabled for client " + name);
        }

        var publisher = new SubmissionPublisher<LlmStreamChunk>(
                Schedulers.boundedElastic().createWorker()::schedule, 256);

        var serverSentEventType = new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {};

        var streamReq = webClient.post()
                .uri("/chat/completions");
        if (bearerToken != null && !bearerToken.isEmpty()) {
            streamReq = streamReq.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        Flux<ServerSentEvent<String>> events = streamReq
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .bodyValue(buildRequestPayload(request, true))
                .retrieve()
                .bodyToFlux(serverSentEventType)
                .timeout(metadata.defaultTimeout());

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
                publisher::closeExceptionally,
                publisher::close);

        return publisher;
    }

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
            // OpenAI-compatible providers vary on JSON output; support the
            // structured-output mode where available and fall back to
            // {"type":"json_object"} for older endpoints.
            ObjectNode rf = root.putObject("response_format");
            rf.put("type", "json_object");
            // Many compatible servers ignore json_schema; include only as hint.
            try {
                rf.set("schema", objectMapper.readTree(json.jsonSchema()));
            } catch (JsonProcessingException ignored) {
                // not all providers accept it; that's OK
            }
        }
        return root;
    }

    private Result<LlmCompletionResponse> parseSyncResponse(JsonNode json, Instant start, String correlationId) {
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
                    "Failed to parse OpenAI-compat response: " + e.getMessage(),
                    correlationId, e));
        }
    }

    private java.util.Optional<LlmStreamChunk> parseStreamChunk(String sseData) {
        try {
            JsonNode json = objectMapper.readTree(sseData);
            JsonNode delta = json.path("choices").path(0).path("delta");
            String content = delta.path("content").asText("");
            String finish = json.path("choices").path(0).path("finish_reason").asText(null);
            if (content.isEmpty() && finish == null) return java.util.Optional.empty();
            return java.util.Optional.of(new LlmStreamChunk(
                    content,
                    finish == null ? null : parseFinishReason(finish),
                    json.path("usage").path("prompt_tokens").asInt(0),
                    json.path("usage").path("completion_tokens").asInt(0)));
        } catch (Exception e) {
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

    private static boolean isRetryable(Throwable t) {
        if (t instanceof WebClientResponseException wcre) {
            int s = wcre.getStatusCode().value();
            return s == 429 || s == 502 || s == 503 || s == 504;
        }
        return t instanceof WebClientRequestException
                || t instanceof java.util.concurrent.TimeoutException;
    }

    private Result<LlmCompletionResponse> translateError(Throwable t, String correlationId) {
        EnrichmentError.Category category;
        String msg;
        if (t instanceof java.util.concurrent.TimeoutException) {
            category = EnrichmentError.Category.TIMEOUT;
            msg = "OpenAI-compat call timed out";
        } else if (t instanceof CallNotPermittedException) {
            category = EnrichmentError.Category.CIRCUIT_OPEN;
            msg = "OpenAI-compat circuit breaker open";
        } else if (t instanceof WebClientResponseException wcre) {
            int s = wcre.getStatusCode().value();
            if (s == 401) { category = EnrichmentError.Category.UNAUTHORISED; }
            else if (s == 403) { category = EnrichmentError.Category.FORBIDDEN; }
            else if (s == 429) { category = EnrichmentError.Category.UPSTREAM_RATE_LIMITED; }
            else if (s >= 500) { category = EnrichmentError.Category.UPSTREAM_UNAVAILABLE; }
            else { category = EnrichmentError.Category.BAD_INPUT; }
            msg = "OpenAI-compat HTTP " + s + ": " + wcre.getResponseBodyAsString();
        } else if (t instanceof WebClientRequestException) {
            category = EnrichmentError.Category.NETWORK;
            msg = "Network error: " + t.getMessage();
        } else {
            category = EnrichmentError.Category.UNKNOWN;
            msg = "Unexpected: " + t.getMessage();
        }
        return Result.failure(new EnrichmentError(category, msg, correlationId, Map.of(), t));
    }
}

package com.jpmc.tfpm.address.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Model-agnostic LLM client. Configuration selects the underlying provider
 * (JPMC internal gateway, OpenAI-compatible endpoint, local Ollama, etc.);
 * structurers and other callers depend only on this interface.
 *
 * <p>See {@code docs/LLM_MODEL_INTEGRATION.md} for the full integration
 * guide, including JPMC-network-specific concerns (ProxySG buffering,
 * internal gateway authentication) and how to add a new provider.
 *
 * <h2>Two delivery modes</h2>
 *
 * <ul>
 *   <li>{@link #complete(LlmCompletionRequest)} — synchronous request/response.
 *       Returns the full response as a single {@link Result}. Suitable for
 *       short completions (structurer JSON output, classification tasks).
 *   <li>{@link #stream(LlmCompletionRequest)} — streaming via reactive-streams
 *       publisher. Chunks arrive as the model produces them. Suitable for
 *       progressive UI rendering and long generative responses.
 * </ul>
 *
 * <p>The address-structurer cascade uses {@code complete(...)} because the
 * cascade is bounded to 500ms total and the structurer needs the full JSON
 * response to parse. Streaming is provided for downstream use cases.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Implementations MUST be {@link ThreadSafe}. Many concurrent threads
 * across HTTP/Kafka/MQ inbound channels will share one client bean instance.
 *
 * <h2>Failure semantics</h2>
 *
 * <p>{@code complete(...)} MUST NOT throw on any expected failure mode
 * (timeout, network error, model unavailable, response parse failure).
 * Instead it returns {@code Result.failure(EnrichmentError)} with an
 * appropriate {@link EnrichmentError.Category}. The caller's retry policy
 * decides whether to retry, fall back to another structurer, or surface
 * the failure to the channel.
 *
 * <p>{@code stream(...)} MUST throw {@link UnsupportedOperationException}
 * synchronously if {@link LlmModelMetadata#supportsStreaming()} is false.
 * Once subscribed, errors flow through the publisher's onError signal as
 * an {@link IllegalStateException} wrapping an {@link EnrichmentError};
 * callers should adapt this back to {@code Result} at their layer boundary.
 */
public interface LlmModelClient {

    /**
     * Stable, configuration-driven identifier for this client instance.
     * Matches the key under {@code llm.clients.<name>} in application.yml.
     * Used in metrics, logs, and audit.
     */
    String name();

    /**
     * What this client can do. Static for the lifetime of the bean.
     */
    LlmModelMetadata metadata();

    /**
     * Synchronous request/response. The full response, validated against
     * any caller-supplied schema, returned in one shot.
     *
     * @param request never null
     * @return never null; {@link Result.Success} on a parsed valid response,
     *         {@link Result.Failure} on any infrastructure or parsing failure.
     */
    Result<LlmCompletionResponse> complete(LlmCompletionRequest request);

    /**
     * Streaming completion via reactive-streams. Subscribers receive chunks
     * as the model emits them; backpressure is honoured per Flow semantics.
     *
     * @throws UnsupportedOperationException synchronously if this client
     *         does not support streaming (check {@code metadata().supportsStreaming()})
     */
    Flow.Publisher<LlmStreamChunk> stream(LlmCompletionRequest request);

    /**
     * Static metadata about a model client. Configured at startup, immutable
     * after.
     *
     * @param providerType        e.g. "jpmc-internal-gateway", "openai-compatible"
     * @param modelId             e.g. "gpt-4o-2024-08-06", "llama3.1:8b"
     * @param supportsStreaming   whether {@link LlmModelClient#stream(LlmCompletionRequest)}
     *                            is functional on this client
     * @param maxInputTokens      provider-advertised limit; 0 if unknown
     * @param maxOutputTokens     provider-advertised limit; 0 if unknown
     * @param defaultTimeout      configured per-call timeout
     */
    record LlmModelMetadata(
            String providerType,
            String modelId,
            boolean supportsStreaming,
            int maxInputTokens,
            int maxOutputTokens,
            Duration defaultTimeout) {

        public LlmModelMetadata {
            Objects.requireNonNull(providerType, "providerType");
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        }
    }

    /**
     * A single completion request. Channel-agnostic; no provider-specific
     * fields. Provider-specific options (top_p, frequency_penalty, etc.)
     * are configured per client at startup, not per call.
     *
     * @param systemPrompt   never null, may be empty
     * @param messages       conversation history (latest message last);
     *                       at least one entry; immutable
     * @param maxTokens      response cap; 0 means use client default
     * @param temperature    0.0..2.0; null means use client default
     * @param outputFormat   how to constrain the response (text vs JSON);
     *                       null means free text
     * @param correlationId  propagated to logs and traces; never null
     * @param metadata       free-form per-call context (user id, use case
     *                       tag); never null, may be empty; immutable
     */
    record LlmCompletionRequest(
            String systemPrompt,
            List<Message> messages,
            int maxTokens,
            Double temperature,
            OutputFormat outputFormat,
            String correlationId,
            Map<String, String> metadata) {

        public LlmCompletionRequest {
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            Objects.requireNonNull(messages, "messages");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(metadata, "metadata");
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("messages must not be empty");
            }
            if (maxTokens < 0) {
                throw new IllegalArgumentException("maxTokens must be >= 0 (0 = client default)");
            }
            if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
                throw new IllegalArgumentException("temperature must be in [0, 2]");
            }
            messages = List.copyOf(messages);
            metadata = Map.copyOf(metadata);
        }

        public record Message(Role role, String content) {
            public Message {
                Objects.requireNonNull(role, "role");
                Objects.requireNonNull(content, "content");
            }

            public enum Role { SYSTEM, USER, ASSISTANT }
        }

        public sealed interface OutputFormat {
            record Text() implements OutputFormat {}
            record Json(String jsonSchema) implements OutputFormat {
                public Json { Objects.requireNonNull(jsonSchema, "jsonSchema"); }
            }
        }
    }

    /**
     * A single completion response (sync mode).
     *
     * @param content              the assistant's response text; for JSON
     *                             output mode, the parsed and validated payload
     *                             as a string; never null
     * @param finishReason         why generation stopped; never null
     * @param inputTokens          prompt tokens consumed
     * @param outputTokens         generated tokens
     * @param totalLatency         wall-clock time from request to last byte
     * @param providerCorrelationId provider's internal request id, for cross-system
     *                             debugging; "" if not provided
     */
    record LlmCompletionResponse(
            String content,
            FinishReason finishReason,
            int inputTokens,
            int outputTokens,
            Duration totalLatency,
            String providerCorrelationId) {

        public LlmCompletionResponse {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(finishReason, "finishReason");
            Objects.requireNonNull(totalLatency, "totalLatency");
            Objects.requireNonNull(providerCorrelationId, "providerCorrelationId");
        }

        public enum FinishReason {
            STOP,            // natural stop
            LENGTH,          // hit max_tokens
            CONTENT_FILTER,  // provider-side content moderation
            TOOL_CALL,       // model requested a tool call (not yet supported here)
            ERROR,           // upstream error mid-stream
            UNKNOWN
        }
    }

    /**
     * One chunk in a streaming response. Subscribers concatenate
     * {@link #deltaContent()} until a chunk arrives with
     * {@link #finishReason()} non-null, signalling end of stream.
     *
     * @param deltaContent         incremental text content; never null,
     *                             may be empty (e.g. for a final chunk
     *                             that only carries finishReason)
     * @param finishReason         non-null on the final chunk only
     * @param inputTokens          included on the final chunk only; 0 otherwise
     * @param outputTokens         included on the final chunk only; 0 otherwise
     */
    record LlmStreamChunk(
            String deltaContent,
            LlmCompletionResponse.FinishReason finishReason,
            int inputTokens,
            int outputTokens) {

        public LlmStreamChunk {
            Objects.requireNonNull(deltaContent, "deltaContent");
        }

        public boolean isFinal() {
            return finishReason != null;
        }
    }
}

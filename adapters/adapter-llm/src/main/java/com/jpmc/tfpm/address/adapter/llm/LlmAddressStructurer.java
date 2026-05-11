package com.jpmc.tfpm.address.adapter.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.Calibrated;
import com.jpmc.tfpm.address.domain.EnrichmentError;
import com.jpmc.tfpm.address.domain.LlmModelClient;
import com.jpmc.tfpm.address.domain.LlmModelClient.LlmCompletionRequest;
import com.jpmc.tfpm.address.domain.LlmModelClient.LlmCompletionResponse;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.Result;
import com.jpmc.tfpm.address.domain.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Structures addresses using an LLM via the {@link LlmModelClient} abstraction.
 * Uses {@code complete(...)} for synchronous request/response within the cascade's
 * 500ms budget. Never throws from {@link #structure(RawAddress)}.
 */
@ThreadSafe
@Calibrated
public final class LlmAddressStructurer implements AddressStructurer {

    private static final Logger LOG = LoggerFactory.getLogger(LlmAddressStructurer.class);

    private final LlmModelClient llmClient;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ObjectMapper objectMapper;
    private final Set<AddressField> allowedFields;

    public LlmAddressStructurer(
            LlmModelClient llmClient,
            PromptTemplateLoader promptTemplateLoader,
            ObjectMapper objectMapper,
            Set<AddressField> allowedFields) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.promptTemplateLoader = Objects.requireNonNull(promptTemplateLoader, "promptTemplateLoader");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.allowedFields = Collections.unmodifiableSet(EnumSet.copyOf(
                Objects.requireNonNull(allowedFields, "allowedFields")));
    }

    @Override
    public String name() {
        return "llm";
    }

    @Override
    public Set<AddressField> supportedFields() {
        return allowedFields;
    }

    @Override
    public StructuringResult structure(RawAddress raw) {
        var start = Instant.now();
        try {
            var rendered = promptTemplateLoader.render(raw);

            var outputFormat = rendered.outputSchema().isEmpty()
                    ? null
                    : new LlmCompletionRequest.OutputFormat.Json(rendered.outputSchema());

            var request = new LlmCompletionRequest(
                    rendered.systemPrompt(),
                    List.of(new LlmCompletionRequest.Message(
                            LlmCompletionRequest.Message.Role.USER,
                            rendered.userMessage())),
                    rendered.maxTokens(),
                    rendered.temperature(),
                    outputFormat,
                    correlationId(),
                    Map.of());

            var completionResult = llmClient.complete(request);

            return switch (completionResult) {
                case Result.Success<LlmCompletionResponse>(var response) -> {
                    var latency = Duration.between(start, Instant.now());
                    var fields = parseResponse(response.content());
                    LOG.debug("LLM returned {} fields in {}ms [model={}]",
                            fields.size(), latency.toMillis(), llmClient.metadata().modelId());
                    yield new StructuringResult(name(), fields, latency,
                            Map.of("model", llmClient.metadata().modelId(),
                                    "inputTokens", response.inputTokens(),
                                    "outputTokens", response.outputTokens(),
                                    "finishReason", response.finishReason().name()));
                }
                case Result.Failure<LlmCompletionResponse>(var error) -> {
                    var latency = Duration.between(start, Instant.now());
                    LOG.warn("LLM completion failed: {} ({}ms)", error.message(), latency.toMillis());
                    yield StructuringResult.empty(name(), latency);
                }
            };
        } catch (Exception e) {
            var latency = Duration.between(start, Instant.now());
            LOG.error("LLM unexpected error ({}ms)", latency.toMillis(), e);
            return StructuringResult.empty(name(), latency);
        }
    }

    private Map<AddressField, FieldValue> parseResponse(String content) {
        var fields = new EnumMap<AddressField, FieldValue>(AddressField.class);
        try {
            var root = objectMapper.readTree(content);
            var fieldsNode = root.path("fields");
            if (fieldsNode.isMissingNode() || !fieldsNode.isObject()) {
                LOG.warn("LLM response missing 'fields' object");
                return Map.of();
            }

            var it = fieldsNode.fields();
            while (it.hasNext()) {
                var entry = it.next();
                try {
                    var field = AddressField.valueOf(entry.getKey());
                    if (!allowedFields.contains(field)) continue;

                    var node = entry.getValue();
                    var value = node.path("value").asText("");
                    var confidence = node.path("confidence").asDouble(0.0);

                    if (!value.isEmpty()) {
                        fields.put(field, new FieldValue(value, confidence));
                    }
                } catch (IllegalArgumentException ignored) {
                    // unknown field name from LLM — skip
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse LLM JSON response", e);
            return Map.of();
        }
        return fields;
    }

    private static String correlationId() {
        // MDC-based correlation ID will be available once MDC propagation is wired
        var mdc = org.slf4j.MDC.get("traceId");
        return mdc != null ? mdc : "";
    }
}

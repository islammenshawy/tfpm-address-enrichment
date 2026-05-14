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

    private final String structurerName;
    private final LlmModelClient llmClient;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ObjectMapper objectMapper;
    private final Set<AddressField> allowedFields;

    public LlmAddressStructurer(
            LlmModelClient llmClient,
            PromptTemplateLoader promptTemplateLoader,
            ObjectMapper objectMapper,
            Set<AddressField> allowedFields) {
        this(llmClient.name(), llmClient, promptTemplateLoader, objectMapper, allowedFields);
    }

    public LlmAddressStructurer(
            String name,
            LlmModelClient llmClient,
            PromptTemplateLoader promptTemplateLoader,
            ObjectMapper objectMapper,
            Set<AddressField> allowedFields) {
        this.structurerName = Objects.requireNonNull(name, "name");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.promptTemplateLoader = Objects.requireNonNull(promptTemplateLoader, "promptTemplateLoader");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.allowedFields = Collections.unmodifiableSet(EnumSet.copyOf(
                Objects.requireNonNull(allowedFields, "allowedFields")));
    }

    @Override
    public String name() {
        return structurerName;
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

    // Map common LLM field name variants to our AddressField enum
    private static final Map<String, AddressField> FIELD_ALIASES = Map.ofEntries(
            Map.entry("CTRY", AddressField.CTRY), Map.entry("COUNTRY", AddressField.CTRY),
            Map.entry("country", AddressField.CTRY), Map.entry("country_code", AddressField.CTRY),
            Map.entry("TWN_NM", AddressField.TWN_NM), Map.entry("CITY", AddressField.TWN_NM),
            Map.entry("city", AddressField.TWN_NM), Map.entry("town", AddressField.TWN_NM),
            Map.entry("TOWN_NAME", AddressField.TWN_NM),
            Map.entry("PST_CD", AddressField.PST_CD), Map.entry("POSTAL_CODE", AddressField.PST_CD),
            Map.entry("postal_code", AddressField.PST_CD), Map.entry("zip", AddressField.PST_CD),
            Map.entry("ZIP_CODE", AddressField.PST_CD), Map.entry("zipcode", AddressField.PST_CD),
            Map.entry("postcode", AddressField.PST_CD),
            Map.entry("CTRY_SUB_DVSN", AddressField.CTRY_SUB_DVSN), Map.entry("STATE", AddressField.CTRY_SUB_DVSN),
            Map.entry("state", AddressField.CTRY_SUB_DVSN), Map.entry("province", AddressField.CTRY_SUB_DVSN),
            Map.entry("region", AddressField.CTRY_SUB_DVSN),
            Map.entry("STRT_NM", AddressField.STRT_NM), Map.entry("STREET", AddressField.STRT_NM),
            Map.entry("street", AddressField.STRT_NM), Map.entry("street_name", AddressField.STRT_NM),
            Map.entry("ADDR_LINE1", AddressField.STRT_NM), Map.entry("road", AddressField.STRT_NM),
            Map.entry("BLDG_NB", AddressField.BLDG_NB), Map.entry("BUILDING_NUMBER", AddressField.BLDG_NB),
            Map.entry("building_number", AddressField.BLDG_NB), Map.entry("house_number", AddressField.BLDG_NB),
            Map.entry("number", AddressField.BLDG_NB),
            Map.entry("BLDG_NM", AddressField.BLDG_NM), Map.entry("BUILDING_NAME", AddressField.BLDG_NM),
            Map.entry("building_name", AddressField.BLDG_NM), Map.entry("building", AddressField.BLDG_NM),
            Map.entry("ADR_LINE", AddressField.ADR_LINE), Map.entry("address_line", AddressField.ADR_LINE)
    );

    private Map<AddressField, FieldValue> parseResponse(String content) {
        var fields = new EnumMap<AddressField, FieldValue>(AddressField.class);
        try {
            // Strip markdown code fences if present
            var json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
            }

            var root = objectMapper.readTree(json);

            // Try "fields" wrapper first, then root-level fields
            var fieldsNode = root.path("fields");
            if (fieldsNode.isMissingNode() || !fieldsNode.isObject()) {
                fieldsNode = root.path("address");
            }
            if (fieldsNode.isMissingNode() || !fieldsNode.isObject()) {
                // Try root as flat fields
                fieldsNode = root;
            }

            var it = fieldsNode.fields();
            while (it.hasNext()) {
                var entry = it.next();
                var fieldName = entry.getKey();

                // Resolve field name via aliases
                var field = FIELD_ALIASES.get(fieldName);
                if (field == null) {
                    try { field = AddressField.valueOf(fieldName); } catch (IllegalArgumentException ignored) {}
                }
                if (field == null || !allowedFields.contains(field)) continue;
                if (fields.containsKey(field)) continue; // first match wins

                var node = entry.getValue();
                String value;
                double confidence;

                if (node.isObject()) {
                    value = node.path("value").asText("");
                    confidence = node.path("confidence").asDouble(0.85);
                } else if (node.isTextual()) {
                    // Flat format: {"CITY": "New York"}
                    value = node.asText("");
                    confidence = 0.85;
                } else {
                    continue;
                }

                if (!value.isEmpty()) {
                    fields.put(field, new FieldValue(value, confidence));
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

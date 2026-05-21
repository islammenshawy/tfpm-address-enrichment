package com.jpmc.tfpm.address.adapter.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and renders address-structuring prompts from a JSON template resource.
 * Supports per-country prompt supplements loaded from the classpath.
 *
 * <p>When a country hint is provided, the matching country supplement is
 * injected into the system prompt to give the LLM country-specific guidance.
 * When no hint is given, the base prompt is used and the LLM infers
 * the country from the address text.
 */
@ThreadSafe
public final class PromptTemplateLoader {

    private static final Logger LOG = LoggerFactory.getLogger(PromptTemplateLoader.class);

    private final String systemPrompt;
    private final String userMessageTemplate;
    private final String outputSchema;
    private final int maxTokens;
    private final double temperature;
    private final Map<String, String> countrySupplements;

    public PromptTemplateLoader(Resource templateResource, ObjectMapper objectMapper) {
        Objects.requireNonNull(templateResource, "templateResource");
        Objects.requireNonNull(objectMapper, "objectMapper");
        try {
            var root = objectMapper.readTree(templateResource.getInputStream());
            this.systemPrompt = root.path("systemPrompt").asText("");
            this.userMessageTemplate = root.path("userMessageTemplate").asText(
                    "Parse this address: {{rawAddress}}");
            this.outputSchema = root.path("outputSchema").asText("");
            this.maxTokens = root.path("maxTokens").asInt(500);
            this.temperature = root.path("temperature").asDouble(0.1);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load prompt template: " + templateResource, e);
        }

        this.countrySupplements = new ConcurrentHashMap<>();
    }

    public RenderedPrompt render(RawAddress raw) {
        var countryHint = raw.countryHint();

        var supplement = "";
        if (countryHint != null && !countryHint.isBlank()) {
            supplement = countrySupplements.getOrDefault(countryHint.toUpperCase(), "");
        }

        var fullSystemPrompt = supplement.isEmpty()
                ? systemPrompt
                : systemPrompt + "\n\nCountry-specific guidance for " + countryHint.toUpperCase() + ":\n" + supplement;

        var userMessage = userMessageTemplate
                .replace("{{rawAddress}}", raw.raw() != null ? raw.raw() : "")
                .replace("{{countryHint}}", countryHint != null ? countryHint : "")
                .replace("{{locale}}", raw.locale() != null ? raw.locale() : "");

        return new RenderedPrompt(fullSystemPrompt, userMessage, outputSchema, maxTokens, temperature);
    }

    /**
     * Register a country-specific prompt supplement. Called during startup
     * to load from classpath resources.
     */
    public void registerCountrySupplement(String countryCode, String supplement) {
        countrySupplements.put(countryCode.toUpperCase(), supplement);
    }

    public record RenderedPrompt(
            String systemPrompt,
            String userMessage,
            String outputSchema,
            int maxTokens,
            double temperature) {
        public RenderedPrompt {
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            Objects.requireNonNull(userMessage, "userMessage");
        }
    }
}

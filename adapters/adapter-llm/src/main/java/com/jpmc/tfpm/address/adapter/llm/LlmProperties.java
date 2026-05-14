package com.jpmc.tfpm.address.adapter.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Externalized configuration for LLM structurer adapters.
 * Supports multiple providers — each becomes a separate AddressStructurer bean.
 *
 * <pre>
 * enrichment:
 *   llm:
 *     providers:
 *       glm:
 *         type: openai-compatible
 *         endpoint: https://api.z.ai/api/coding/paas/v4
 *         api-key: ${GLM_API_KEY:}
 *         model: glm-5.1
 *       azure-gpt:
 *         type: azure-openai
 *         endpoint: ${AZURE_OPENAI_ENDPOINT:}
 *         api-key: ${AZURE_OPENAI_API_KEY:}
 *         model: gpt-4.1-mini
 * </pre>
 */
@ConfigurationProperties(prefix = "enrichment.llm")
public record LlmProperties(
        boolean enabled,
        Map<String, ProviderConfig> providers,
        List<String> fieldsAllowed,
        PromptProperties prompt) {

    /** Single LLM provider configuration. */
    public record ProviderConfig(
            String type,
            String endpoint,
            String apiKey,
            String model,
            int timeoutMs,
            String apiVersion) {
        public ProviderConfig {
            if (type == null) type = "openai-compatible";
            if (timeoutMs <= 0) timeoutMs = 15000;
        }
    }

    public record PromptProperties(String templateResource) {}
}

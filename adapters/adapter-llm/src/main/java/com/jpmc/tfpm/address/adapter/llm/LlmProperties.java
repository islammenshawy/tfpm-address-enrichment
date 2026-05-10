package com.jpmc.tfpm.address.adapter.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Externalized configuration for the LLM structurer adapter.
 * Bound to {@code enrichment.llm.*} in application.yml.
 */
@ConfigurationProperties(prefix = "enrichment.llm")
public record LlmProperties(
        boolean enabled,
        String endpoint,
        int timeoutMs,
        List<String> fieldsAllowed,
        BulkheadProperties bulkhead,
        PromptProperties prompt) {

    public record BulkheadProperties(int maxConcurrentCalls, int maxWaitDurationMs) {}
    public record PromptProperties(String templateResource) {}
}

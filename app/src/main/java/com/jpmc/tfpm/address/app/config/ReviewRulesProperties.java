package com.jpmc.tfpm.address.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "enrichment.review-rules")
public record ReviewRulesProperties(List<ReviewRule> rules) {
    public ReviewRulesProperties {
        rules = rules != null ? List.copyOf(rules) : List.of();
    }
    public record ReviewRule(String name, boolean enabled, String type,
                             String description, Map<String, Object> params) {
        public ReviewRule {
            params = params != null ? Map.copyOf(params) : Map.of();
        }
    }
}

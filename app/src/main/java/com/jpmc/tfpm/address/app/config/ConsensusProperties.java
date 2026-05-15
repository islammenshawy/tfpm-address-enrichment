package com.jpmc.tfpm.address.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "enrichment.consensus")
public record ConsensusProperties(
        Map<String, Map<String, Double>> sourceWeights) {

    public ConsensusProperties {
        sourceWeights = sourceWeights != null ? Map.copyOf(sourceWeights) : Map.of();
    }
}

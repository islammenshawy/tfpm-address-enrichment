package com.jpmc.tfpm.address.app.compliance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "enrichment.compliance")
public record ComplianceProperties(
        boolean enabled,
        Map<String, Double> fieldConfidenceFloor,
        double overallConfidenceFloor,
        String highRiskCountriesResource,
        String patternTriggersResource,
        String failSafeAction,
        boolean shadowMode) {

    public ComplianceProperties {
        if (fieldConfidenceFloor == null) fieldConfidenceFloor = Map.of();
        if (highRiskCountriesResource == null) highRiskCountriesResource = "";
        if (patternTriggersResource == null) patternTriggersResource = "";
        if (failSafeAction == null) failSafeAction = "CONSERVATIVE";
    }
}

package com.jpmc.tfpm.address.inbound.http;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP response body for address enrichment.
 */
public record EnrichmentHttpResponse(
        String correlationId,
        String outcome,
        Map<String, FieldResponse> fields,
        double overallConfidence,
        Long resultRowId,
        Instant processedAt) {

    public record FieldResponse(String value, double confidence) {}

    public static EnrichmentHttpResponse from(EnrichmentResult result) {
        var fields = new LinkedHashMap<String, FieldResponse>();
        for (var entry : result.structuredAddress().fields().entrySet()) {
            fields.put(entry.getKey().name(),
                    new FieldResponse(entry.getValue().value(), entry.getValue().confidence()));
        }

        return new EnrichmentHttpResponse(
                result.correlationId(),
                result.outcome().name(),
                fields,
                result.overallConfidence(),
                result.resultRowId(),
                result.processedAt());
    }
}

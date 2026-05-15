package com.jpmc.tfpm.address.inbound.http;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.ConsensusResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP response body for address enrichment.
 */
public record EnrichmentHttpResponse(
        String correlationId,
        String outcome,
        Map<String, FieldResponse> fields,
        double overallConfidence,
        List<String> sources,
        ConsensusResponse consensus,
        Long resultRowId,
        Instant processedAt) {

    public record FieldResponse(String value, double confidence) {}

    public record ConsensusResponse(
            int sourceCount,
            int agreementCount,
            int disagreementCount,
            double overallConsensus,
            Map<String, FieldConsensusResponse> fields) {}

    public record FieldConsensusResponse(
            boolean agreed,
            String consensusValue,
            Map<String, String> sourceValues) {}

    public static EnrichmentHttpResponse from(EnrichmentResult result) {
        var fields = new LinkedHashMap<String, FieldResponse>();
        for (var entry : result.structuredAddress().fields().entrySet()) {
            fields.put(entry.getKey().name(),
                    new FieldResponse(entry.getValue().value(), entry.getValue().confidence()));
        }

        ConsensusResponse consensusResp = null;
        if (result.consensus() != null) {
            var cr = result.consensus();
            var fieldConsensus = new LinkedHashMap<String, FieldConsensusResponse>();
            for (var fc : cr.fieldConsensus().entrySet()) {
                var v = fc.getValue();
                fieldConsensus.put(fc.getKey().name(),
                        new FieldConsensusResponse(v.agreed(), v.consensusValue(), v.alternatives()));
            }
            consensusResp = new ConsensusResponse(
                    cr.sourceCount(), cr.agreementCount(), cr.disagreementCount(),
                    cr.overallConsensus(), fieldConsensus);
        }

        return new EnrichmentHttpResponse(
                result.correlationId(),
                result.outcome().name(),
                fields,
                result.overallConfidence(),
                result.sources(),
                consensusResp,
                result.resultRowId(),
                result.processedAt());
    }
}

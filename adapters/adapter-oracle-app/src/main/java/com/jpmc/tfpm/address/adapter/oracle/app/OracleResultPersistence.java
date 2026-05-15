package com.jpmc.tfpm.address.adapter.oracle.app;

import com.jpmc.tfpm.address.domain.CascadeResult;
import com.jpmc.tfpm.address.domain.ResultPersistence;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.jooq.impl.DSL.*;

/**
 * Oracle-backed persistence for enrichment results.
 */
@ThreadSafe
public class OracleResultPersistence implements ResultPersistence {

    private static final Logger LOG = LoggerFactory.getLogger(OracleResultPersistence.class);

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public OracleResultPersistence(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    @Retryable(retryFor = TransientDataAccessException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public long persistResult(EnrichmentRequest request, CascadeResult cascadeResult) {
        var address = cascadeResult.structuredAddress();
        var fieldsJson = serializeFields(address);
        var traceJson = serializeTrace(cascadeResult);
        var requiresReview = !address.meetsSr2026Minimum()
                || cascadeResult.overallConfidence() < 0.70 ? "Y" : "N";

        var resultId = dsl.insertInto(table("STRUCTURING_RESULTS"))
                .columns(
                        field("CORRELATION_ID"),
                        field("SOURCE_CHANNEL"),
                        field("RAW_ADDRESS"),
                        field("COUNTRY_HINT"),
                        field("FIELDS_JSON"),
                        field("STRUCTURER_TRACE"),
                        field("OVERALL_CONFIDENCE"),
                        field("REQUIRES_REVIEW"),
                        field("CREATED_AT"))
                .values(
                        request.correlationId(),
                        request.sourceChannel().name(),
                        request.address().raw(),
                        request.address().countryHint(),
                        fieldsJson,
                        traceJson,
                        cascadeResult.overallConfidence(),
                        requiresReview,
                        LocalDateTime.now())
                .returningResult(field("RESULT_ID"))
                .fetchOne();

        long id = resultId != null ? resultId.get(field("RESULT_ID", Long.class)) : -1L;
        LOG.debug("Persisted result id={} [corrId={}]", id, request.correlationId());
        return id;
    }

    @Override
    public Optional<EnrichmentResult> loadResult(long resultRowId, String correlationId) {
        var row = dsl.select(
                        field("CORRELATION_ID"),
                        field("FIELDS_JSON"),
                        field("OVERALL_CONFIDENCE"),
                        field("REQUIRES_REVIEW"),
                        field("CREATED_AT"))
                .from(table("STRUCTURING_RESULTS"))
                .where(field("RESULT_ID").eq(resultRowId))
                .fetchOne();

        if (row == null) return Optional.empty();

        var fieldsJson = row.get(field("FIELDS_JSON"), String.class);
        var address = deserializeFields(fieldsJson);
        var confidence = row.get(field("OVERALL_CONFIDENCE"), Double.class);
        var requiresReview = "Y".equals(row.get(field("REQUIRES_REVIEW"), String.class));

        var outcome = requiresReview
                ? EnrichmentResult.Outcome.REQUIRES_REVIEW
                : EnrichmentResult.Outcome.SUCCESS;

        return Optional.of(new EnrichmentResult(
                correlationId, outcome, address,
                confidence != null ? confidence : 0.0,
                resultRowId, Instant.now()));
    }

    @Override
    @Retryable(retryFor = TransientDataAccessException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public void writeToExceptionQueue(long resultRowId, String reason) {
        dsl.insertInto(table("EXCEPTION_QUEUE"))
                .columns(
                        field("RESULT_ID"),
                        field("REASON"),
                        field("STATUS"),
                        field("VERSION"),
                        field("CREATED_AT"))
                .values(
                        resultRowId,
                        reason,
                        "OPEN",
                        1,
                        LocalDateTime.now())
                .execute();
        LOG.debug("Wrote to exception queue: resultId={}, reason={}", resultRowId, reason);
    }

    private String serializeFields(StructuredAddress address) {
        var map = new LinkedHashMap<String, Object>();
        for (var entry : address.fields().entrySet()) {
            map.put(entry.getKey().name(), Map.of(
                    "value", entry.getValue().value(),
                    "confidence", entry.getValue().confidence()));
        }
        return toJson(map);
    }

    private String serializeTrace(CascadeResult result) {
        var traces = result.structurerTrace().stream()
                .map(r -> Map.of(
                        "structurer", r.structurerName(),
                        "latencyMs", r.latency().toMillis(),
                        "fieldCount", r.fields().size()))
                .toList();
        return toJson(traces);
    }

    private StructuredAddress deserializeFields(String json) {
        if (json == null || json.isBlank()) return StructuredAddress.empty();
        try {
            var map = objectMapper.readValue(json, Map.class);
            var builder = StructuredAddress.builder();
            for (var entry : ((Map<String, Map<String, Object>>) map).entrySet()) {
                try {
                    var field = AddressField.valueOf(entry.getKey());
                    var value = String.valueOf(entry.getValue().get("value"));
                    var confidence = ((Number) entry.getValue().get("confidence")).doubleValue();
                    builder.put(field, new FieldValue(value, confidence));
                } catch (IllegalArgumentException ignored) {
                    // unknown field name — skip
                }
            }
            return builder.build();
        } catch (Exception e) {
            LOG.warn("Failed to deserialize FIELDS_JSON: {}", e.getMessage());
            return StructuredAddress.empty();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            LOG.error("JSON serialization failed", e);
            return "{}";
        }
    }
}

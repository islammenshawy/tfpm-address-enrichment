package com.jpmc.tfpm.address.adapter.oracle.app;

import com.jpmc.tfpm.address.domain.CascadeResult;
import com.jpmc.tfpm.address.domain.EnrichmentError;
import com.jpmc.tfpm.address.domain.ResultPersistence;
import com.jpmc.tfpm.address.domain.Result;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
    @Retryable(retryFor = {TransientDataAccessException.class, org.jooq.exception.DataAccessException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public Result<Long> persistResult(EnrichmentRequest request, CascadeResult cascadeResult, boolean requiresReview) {
        try {
            var address = cascadeResult.structuredAddress();
            var fieldsJson = serializeFields(address);
            var traceJson = serializeTrace(cascadeResult);
            var reviewFlag = requiresReview ? "Y" : "N";

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
                            reviewFlag,
                            LocalDateTime.now())
                    .returningResult(field("RESULT_ID"))
                    .fetchOne();

            if (resultId == null) {
                LOG.error("No generated key returned for persisted result [corrId={}]", request.correlationId());
                return Result.failure(EnrichmentError.of(
                        EnrichmentError.Category.UNKNOWN,
                        "Database did not return a generated key for the persisted result",
                        request.correlationId()));
            }

            long id = resultId.get(field("RESULT_ID", Long.class));
            LOG.debug("Persisted result id={} [corrId={}]", id, request.correlationId());
            return Result.success(id);
        } catch (Exception e) {
            LOG.error("Failed to persist result [corrId={}]", request.correlationId(), e);
            return Result.failure(EnrichmentError.of(
                    EnrichmentError.Category.DATABASE_CONNECTION,
                    "Failed to persist enrichment result: " + e.getMessage(),
                    request.correlationId(), e));
        }
    }

    @Override
    public Result<Optional<EnrichmentResult>> loadResult(long resultRowId, String correlationId) {
        try {
            var row = dsl.select(
                            field("CORRELATION_ID"),
                            field("FIELDS_JSON"),
                            field("OVERALL_CONFIDENCE"),
                            field("REQUIRES_REVIEW"),
                            field("CREATED_AT"))
                    .from(table("STRUCTURING_RESULTS"))
                    .where(field("RESULT_ID").eq(resultRowId))
                    .fetchOne();

            if (row == null) return Result.success(Optional.empty());

            var fieldsJson = row.get(field("FIELDS_JSON"), String.class);
            var address = deserializeFields(fieldsJson);
            var confidence = row.get(field("OVERALL_CONFIDENCE"), Double.class);
            var requiresReview = "Y".equals(row.get(field("REQUIRES_REVIEW"), String.class));

            var outcome = requiresReview
                    ? EnrichmentResult.Outcome.REQUIRES_REVIEW
                    : EnrichmentResult.Outcome.SUCCESS;

            return Result.success(Optional.of(new EnrichmentResult(
                    correlationId, outcome, address,
                    confidence != null ? confidence : 0.0,
                    resultRowId, Instant.now())));
        } catch (Exception e) {
            LOG.error("Failed to load result id={} [corrId={}]", resultRowId, correlationId, e);
            return Result.failure(EnrichmentError.of(
                    EnrichmentError.Category.DATABASE_CONNECTION,
                    "Failed to load enrichment result: " + e.getMessage(),
                    correlationId, e));
        }
    }

    @Override
    @Retryable(retryFor = {TransientDataAccessException.class, org.jooq.exception.DataAccessException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
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

    private static final TypeReference<Map<String, Map<String, Object>>> FIELDS_TYPE_REF =
            new TypeReference<>() {};

    private StructuredAddress deserializeFields(String json) {
        if (json == null || json.isBlank()) return StructuredAddress.empty();
        try {
            Map<String, Map<String, Object>> map = objectMapper.readValue(json, FIELDS_TYPE_REF);
            var builder = StructuredAddress.builder();
            for (var entry : map.entrySet()) {
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

package com.jpmc.tfpm.address.adapter.oracle.app;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.FieldAttributionWriter;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.*;

/**
 * Writes per-field attribution rows to FIELD_ATTRIBUTIONS.
 * Calibrates ALL fields (not just selected) using the injected calibrators.
 */
@ThreadSafe
public class JooqFieldAttributionWriter implements FieldAttributionWriter {

    private static final Logger LOG = LoggerFactory.getLogger(JooqFieldAttributionWriter.class);

    private final DSLContext dsl;
    private final Map<String, ConfidenceCalibrator> calibrators;

    public JooqFieldAttributionWriter(DSLContext dsl, List<ConfidenceCalibrator> calibratorList) {
        this.dsl = dsl;
        var map = new java.util.HashMap<String, ConfidenceCalibrator>();
        for (var c : calibratorList) {
            map.put(c.structurerName(), c);
        }
        this.calibrators = Map.copyOf(map);
    }

    @Override
    @Retryable(retryFor = {TransientDataAccessException.class, org.jooq.exception.DataAccessException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public void writeAttributions(long resultId, List<StructuringResult> trace,
                                  StructuredAddress merged, String countryHint) {
        try {
            var now = LocalDateTime.now();
            for (var result : trace) {
                for (var entry : result.fields().entrySet()) {
                    var field = entry.getKey();
                    var fv = entry.getValue();
                    var mergedFv = merged.get(field);
                    boolean wasSelected = mergedFv.isPresent()
                            && mergedFv.get().value().equals(fv.value());

                    // Calibrate confidence for ALL fields, not just selected
                    double calibrated = calibrate(result.structurerName(),
                            fv.confidence(), field, countryHint);

                    dsl.insertInto(table("FIELD_ATTRIBUTIONS"))
                            .columns(
                                    field("RESULT_ID"),
                                    field("FIELD_NAME"),
                                    field("STRUCTURER_NAME"),
                                    field("STRUCTURER_VERSION"),
                                    field("RAW_CONFIDENCE"),
                                    field("CALIBRATED_CONFIDENCE"),
                                    field("WAS_SELECTED"),
                                    field("FIELD_VALUE"),
                                    field("LATENCY_MS"),
                                    field("COUNTRY_HINT"),
                                    field("CREATED_AT"))
                            .values(
                                    resultId,
                                    field.name(),
                                    result.structurerName(),
                                    result.diagnostics().getOrDefault("version", "unknown").toString(),
                                    fv.confidence(),
                                    calibrated,
                                    wasSelected ? "Y" : "N",
                                    fv.value(),
                                    result.latency().toMillis(),
                                    countryHint,
                                    now)
                            .execute();
                }
            }
            LOG.debug("Wrote field attributions for resultId={}", resultId);
        } catch (Exception e) {
            LOG.error("Failed to write field attributions for resultId={}", resultId, e);
        }
    }

    private double calibrate(String structurerName, double raw, AddressField field, String countryHint) {
        var calibrator = calibrators.get(structurerName);
        if (calibrator == null) {
            return Math.max(0.0, Math.min(1.0, raw));
        }
        return calibrator.calibrate(raw, field, countryHint);
    }
}

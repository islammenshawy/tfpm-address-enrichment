package com.jpmc.tfpm.address.adapter.oracle.app;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.FieldAttributionWriter;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.jooq.DSLContext;
import org.jooq.InsertValuesStepN;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

import static org.jooq.impl.DSL.*;

/**
 * Writes per-field attribution rows to FIELD_ATTRIBUTIONS.
 * One row per (result, field, structurer) combination in the cascade trace.
 */
@ThreadSafe
public final class JooqFieldAttributionWriter implements FieldAttributionWriter {

    private static final Logger LOG = LoggerFactory.getLogger(JooqFieldAttributionWriter.class);

    private final DSLContext dsl;

    public JooqFieldAttributionWriter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
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
                                    fv.confidence(),
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
}

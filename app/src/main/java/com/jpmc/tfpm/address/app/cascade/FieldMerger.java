package com.jpmc.tfpm.address.app.cascade;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Per-field voting merger. For each {@link AddressField}, picks the
 * structurer result with the highest <em>calibrated</em> confidence.
 * Ties are broken by cascade order (earlier structurer wins).
 */
@ThreadSafe
public final class FieldMerger {

    private static final Logger LOG = LoggerFactory.getLogger(FieldMerger.class);

    private final Map<String, ConfidenceCalibrator> calibrators;

    public FieldMerger(List<ConfidenceCalibrator> calibratorList) {
        this.calibrators = calibratorList.stream()
                .collect(Collectors.toUnmodifiableMap(
                        ConfidenceCalibrator::structurerName,
                        c -> c));
    }

    /**
     * Merge results from multiple structurers into a single
     * {@link StructuredAddress} using per-field highest-calibrated-confidence
     * voting.
     *
     * @param results     structurer results in cascade order
     * @param countryHint ISO 3166-1 alpha-2 or empty
     * @return merged address; may be empty if no structurer produced fields
     */
    public StructuredAddress merge(List<StructuringResult> results, String countryHint) {
        if (results.isEmpty()) {
            return StructuredAddress.empty();
        }

        var builder = StructuredAddress.builder();

        for (var field : AddressField.values()) {
            double bestConfidence = -1.0;
            FieldValue bestValue = null;

            for (var result : results) {
                var fv = result.fields().get(field);
                if (fv == null || fv.value().isEmpty()) continue;

                double calibrated = calibrate(result.structurerName(), fv.confidence(), field, countryHint);
                if (calibrated > bestConfidence) {
                    bestConfidence = calibrated;
                    bestValue = new FieldValue(fv.value(), calibrated);
                }
            }

            if (bestValue != null) {
                builder.put(field, bestValue);
            }
        }

        return builder.build();
    }

    private double calibrate(String structurerName, double raw, AddressField field, String countryHint) {
        var calibrator = calibrators.get(structurerName);
        if (calibrator == null) {
            LOG.warn("No calibrator for structurer '{}'; using raw confidence clamped to [0,1]", structurerName);
            return Math.max(0.0, Math.min(1.0, raw));
        }
        return calibrator.calibrate(raw, field, countryHint);
    }
}

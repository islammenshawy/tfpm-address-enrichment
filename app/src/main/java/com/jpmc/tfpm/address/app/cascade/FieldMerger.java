package com.jpmc.tfpm.address.app.cascade;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * {@link StructuredAddress}. Strategy:
     *
     * <ol>
     *   <li><b>Consensus first</b> — if 2+ sources agree on a value
     *       (case-insensitive), use that value regardless of individual
     *       confidence. Consensus trumps single-source confidence.</li>
     *   <li><b>Highest confidence fallback</b> — if no consensus, pick
     *       the value with highest calibrated confidence.</li>
     * </ol>
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
            // Collect all values for this field with their calibrated confidence
            var candidates = new ArrayList<CalibratedCandidate>();
            for (var result : results) {
                var fv = result.fields().get(field);
                if (fv == null || fv.value().isEmpty()) continue;
                double calibrated = calibrate(result.structurerName(), fv.confidence(), field, countryHint);
                candidates.add(new CalibratedCandidate(fv.value(), calibrated, result.structurerName()));
            }

            if (candidates.isEmpty()) continue;

            // Step 1: Check for consensus (2+ sources agree, case-insensitive)
            var consensusValue = findConsensusValue(candidates);
            if (consensusValue != null) {
                builder.put(field, consensusValue);
                continue;
            }

            // Step 2: Fallback to highest calibrated confidence
            var best = candidates.stream()
                    .max((a, b) -> Double.compare(a.confidence, b.confidence))
                    .get();
            builder.put(field, new FieldValue(best.value, best.confidence));
        }

        return builder.build();
    }

    /**
     * Find a value that 2+ sources agree on (case-insensitive).
     * Returns the value with the highest confidence among the agreeing sources,
     * with confidence boosted to reflect consensus agreement.
     */
    private static FieldValue findConsensusValue(List<CalibratedCandidate> candidates) {
        if (candidates.size() < 2) return null;

        // Group by normalized value (lowercase trim)
        var groups = new LinkedHashMap<String, List<CalibratedCandidate>>();
        for (var c : candidates) {
            groups.computeIfAbsent(c.value.toLowerCase().trim(), k -> new ArrayList<>()).add(c);
        }

        // Find the largest group with 2+ members
        List<CalibratedCandidate> bestGroup = null;
        for (var group : groups.values()) {
            if (group.size() >= 2) {
                if (bestGroup == null || group.size() > bestGroup.size()) {
                    bestGroup = group;
                }
            }
        }

        if (bestGroup == null) return null;

        // Use the original (non-lowercased) value from the highest-confidence source in the group
        var best = bestGroup.stream()
                .max((a, b) -> Double.compare(a.confidence, b.confidence))
                .get();

        // Boost confidence: consensus of N sources is stronger than any single source
        double boosted = Math.min(1.0, best.confidence * (1.0 + 0.1 * (bestGroup.size() - 1)));

        LOG.debug("Consensus merge: '{}' agreed by {} (conf {} → {})",
                best.value,
                bestGroup.stream().map(c -> c.structurer).toList(),
                String.format("%.2f", best.confidence),
                String.format("%.2f", boosted));

        return new FieldValue(best.value, boosted);
    }

    private record CalibratedCandidate(String value, double confidence, String structurer) {}

    /**
     * Exposes the calibrator map for use by the cascade early-exit check
     * (avoids redundant full merge).
     */
    Map<String, ConfidenceCalibrator> calibratorMap() {
        return calibrators;
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

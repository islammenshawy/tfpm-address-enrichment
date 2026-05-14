package com.jpmc.tfpm.address.app.cascade;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.ConsensusResult;
import com.jpmc.tfpm.address.domain.ConsensusResult.FieldConsensus;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Analyzes agreement/disagreement across multiple structurer outputs.
 * Minimum 2 sources required for meaningful consensus.
 *
 * <p>Rules:
 * <ul>
 *   <li>All structurers agree on a field value → consensus = 1.0, no flag</li>
 *   <li>Majority agree (2/3+) → consensus = 0.75, minor flag</li>
 *   <li>All disagree → consensus = 0.0, flag for review</li>
 *   <li>Only 1 structurer produced the field → consensus = 0.5, flag as unverified</li>
 * </ul>
 */
@ThreadSafe
public final class ConsensusAnalyzer {

    private static final Logger LOG = LoggerFactory.getLogger(ConsensusAnalyzer.class);

    /**
     * Analyze consensus across structurer trace results.
     *
     * @param trace   list of structuring results (at least 2 for meaningful consensus)
     * @param merged  the final merged structured address (from FieldMerger)
     * @return consensus analysis with per-field agreement details
     */
    public ConsensusResult analyze(List<StructuringResult> trace, StructuredAddress merged) {
        if (trace.isEmpty()) {
            return new ConsensusResult(Map.of(), 0.0, 0, 0, 0, Set.of());
        }

        // Count structurers that actually produced fields (non-empty)
        var activeStructurers = trace.stream()
                .filter(t -> !t.fields().isEmpty())
                .map(StructuringResult::structurerName)
                .distinct()
                .toList();
        int sourceCount = activeStructurers.size();

        // Collect all fields across all structurers
        var allFields = EnumSet.noneOf(AddressField.class);
        for (var result : trace) {
            allFields.addAll(result.fields().keySet());
        }

        var fieldConsensus = new EnumMap<AddressField, FieldConsensus>(AddressField.class);
        var flaggedFields = EnumSet.noneOf(AddressField.class);
        int agreements = 0;
        int disagreements = 0;

        for (var field : allFields) {
            // Collect all values for this field across structurers
            var valuesByStructurer = new LinkedHashMap<String, String>(); // structurerName → value
            for (var result : trace) {
                var fv = result.fields().get(field);
                if (fv != null && !fv.value().isBlank()) {
                    valuesByStructurer.put(result.structurerName(), fv.value());
                }
            }

            if (valuesByStructurer.isEmpty()) continue;

            var mergedValue = merged.get(field).map(fv -> fv.value()).orElse("");
            var sources = new HashSet<>(valuesByStructurer.keySet());

            if (valuesByStructurer.size() == 1) {
                // Only one source — unverified, flag it
                flaggedFields.add(field);
                disagreements++;
                fieldConsensus.put(field, new FieldConsensus(
                        field, false, mergedValue,
                        Map.of(valuesByStructurer.entrySet().iterator().next().getKey(),
                                valuesByStructurer.entrySet().iterator().next().getValue()),
                        sources));
            } else {
                // Multiple sources — check agreement (case-insensitive)
                var normalizedValues = new HashMap<String, List<String>>();
                for (var entry : valuesByStructurer.entrySet()) {
                    normalizedValues.computeIfAbsent(
                            entry.getValue().toLowerCase().trim(),
                            k -> new ArrayList<>()).add(entry.getKey());
                }

                if (normalizedValues.size() == 1) {
                    // All agree
                    agreements++;
                    fieldConsensus.put(field, new FieldConsensus(
                            field, true, mergedValue, Map.of(), sources));
                } else {
                    // Disagreement
                    disagreements++;
                    flaggedFields.add(field);
                    fieldConsensus.put(field, new FieldConsensus(
                            field, false, mergedValue, valuesByStructurer, sources));
                    LOG.debug("Disagreement on {}: {}", field, valuesByStructurer);
                }
            }
        }

        int totalFields = agreements + disagreements;
        double overallConsensus = totalFields > 0 ? (double) agreements / totalFields : 0.0;

        return new ConsensusResult(
                fieldConsensus, overallConsensus,
                agreements, disagreements, sourceCount, flaggedFields);
    }
}

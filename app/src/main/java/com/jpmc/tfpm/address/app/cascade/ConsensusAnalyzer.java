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
 * Weighted consensus analysis across multiple structurer outputs.
 *
 * <p>Each structurer has a per-country weight reflecting its reliability.
 * Only disagreements between <b>strong sources</b> (weight ≥ 0.5) trigger
 * a compliance flag. Weak-source disagreements (e.g., libpostal on CN)
 * are logged as "expected divergence" but don't flag.
 *
 * <p>When no country is detected, default weights apply (all equal).
 */
@ThreadSafe
public final class ConsensusAnalyzer {

    private static final Logger LOG = LoggerFactory.getLogger(ConsensusAnalyzer.class);
    private static final double STRONG_SOURCE_THRESHOLD = 0.5;

    // Per-structurer, per-country weights. Key: structurerName, Value: Map<countryCode, weight>
    // "default" key = fallback weight when country not listed
    private final Map<String, Map<String, Double>> sourceWeights;

    public ConsensusAnalyzer() {
        this(Map.of());
    }

    public ConsensusAnalyzer(Map<String, Map<String, Double>> sourceWeights) {
        this.sourceWeights = sourceWeights != null ? Map.copyOf(sourceWeights) : Map.of();
    }

    /**
     * Analyze consensus with per-country weighted voting.
     *
     * @param trace       structuring results from all sources
     * @param merged      final merged address from FieldMerger
     * @param countryHint ISO 3166-1 alpha-2 or empty — used to look up weights.
     *                    If empty AND no CTRY field detected, all weights default to 1.0.
     */
    public ConsensusResult analyze(List<StructuringResult> trace, StructuredAddress merged,
                                    String countryHint) {
        if (trace.isEmpty()) {
            return new ConsensusResult(Map.of(), 0.0, 0, 0, 0, Set.of());
        }

        // Determine country: use hint, or try to extract from merged CTRY field
        var country = (countryHint != null && !countryHint.isBlank())
                ? countryHint.toUpperCase()
                : merged.get(AddressField.CTRY).map(fv -> fv.value().toUpperCase()).orElse("");

        var activeStructurers = trace.stream()
                .filter(t -> !t.fields().isEmpty())
                .map(StructuringResult::structurerName)
                .distinct().toList();
        int sourceCount = activeStructurers.size();

        var allFields = EnumSet.noneOf(AddressField.class);
        for (var result : trace) allFields.addAll(result.fields().keySet());

        var fieldConsensus = new EnumMap<AddressField, FieldConsensus>(AddressField.class);
        var flaggedFields = EnumSet.noneOf(AddressField.class);
        int agreements = 0;
        int disagreements = 0;

        for (var field : allFields) {
            var valuesByStructurer = new LinkedHashMap<String, String>();
            for (var result : trace) {
                var fv = result.fields().get(field);
                if (fv != null && !fv.value().isBlank())
                    valuesByStructurer.put(result.structurerName(), fv.value());
            }
            if (valuesByStructurer.isEmpty()) continue;

            var mergedValue = merged.get(field).map(fv -> fv.value()).orElse("");
            var sources = new HashSet<>(valuesByStructurer.keySet());

            if (valuesByStructurer.size() == 1) {
                // Single source — unverified
                var src = valuesByStructurer.keySet().iterator().next();
                var w = getWeight(src, country);
                if (w >= STRONG_SOURCE_THRESHOLD) {
                    // Strong single source — acceptable but not verified
                    agreements++;
                    fieldConsensus.put(field, new FieldConsensus(field, true, mergedValue, Map.of(), sources));
                } else {
                    // Weak single source — flag
                    disagreements++;
                    flaggedFields.add(field);
                    fieldConsensus.put(field, new FieldConsensus(field, false, mergedValue, valuesByStructurer, sources));
                }
            } else {
                // Multiple sources — weighted consensus
                var normalizedGroups = new HashMap<String, List<String>>();
                for (var entry : valuesByStructurer.entrySet()) {
                    normalizedGroups.computeIfAbsent(
                            entry.getValue().toLowerCase().trim(),
                            k -> new ArrayList<>()).add(entry.getKey());
                }

                if (normalizedGroups.size() == 1) {
                    // All agree
                    agreements++;
                    fieldConsensus.put(field, new FieldConsensus(field, true, mergedValue, Map.of(), sources));
                } else {
                    // Disagreement — check if it's strong-source disagreement or expected divergence
                    boolean strongDisagreement = isStrongDisagreement(normalizedGroups, country);

                    if (strongDisagreement) {
                        // Real disagreement between strong sources → flag
                        disagreements++;
                        flaggedFields.add(field);
                        fieldConsensus.put(field, new FieldConsensus(
                                field, false, mergedValue, valuesByStructurer, sources));
                        LOG.debug("Strong disagreement on {}: {}", field, valuesByStructurer);
                    } else {
                        // Expected divergence (only weak sources disagree with strong consensus)
                        agreements++;
                        fieldConsensus.put(field, new FieldConsensus(
                                field, true, mergedValue, valuesByStructurer, sources));
                        LOG.debug("Expected divergence on {} (weak source): {}", field, valuesByStructurer);
                    }
                }
            }
        }

        int totalFields = agreements + disagreements;
        double overallConsensus = totalFields > 0 ? (double) agreements / totalFields : 0.0;

        return new ConsensusResult(fieldConsensus, overallConsensus,
                agreements, disagreements, sourceCount, flaggedFields);
    }

    /** Backward-compatible: no country hint. */
    public ConsensusResult analyze(List<StructuringResult> trace, StructuredAddress merged) {
        return analyze(trace, merged, "");
    }

    /**
     * Check if the disagreement involves strong sources disagreeing with each other.
     * If only weak sources (weight < 0.5) diverge from the strong consensus, it's expected.
     */
    private boolean isStrongDisagreement(Map<String, List<String>> normalizedGroups, String country) {
        // Find groups with strong sources
        var strongGroupValues = new HashSet<String>();
        for (var entry : normalizedGroups.entrySet()) {
            for (var src : entry.getValue()) {
                if (getWeight(src, country) >= STRONG_SOURCE_THRESHOLD) {
                    strongGroupValues.add(entry.getKey());
                    break;
                }
            }
        }
        // If 2+ different values have strong-source backing → real disagreement
        return strongGroupValues.size() > 1;
    }

    private double getWeight(String structurerName, String country) {
        var structurerWeights = sourceWeights.get(structurerName);
        if (structurerWeights == null) return 1.0; // default: full weight

        if (!country.isEmpty()) {
            var countryWeight = structurerWeights.get(country);
            if (countryWeight != null) return countryWeight;
        }

        var defaultWeight = structurerWeights.get("default");
        return defaultWeight != null ? defaultWeight : 1.0;
    }
}

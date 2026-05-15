package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Result of consensus analysis across multiple structurers.
 * Tracks per-field agreement, disagreement, and overall consensus score.
 *
 * <p>Used by the compliance router to flag addresses where structurers
 * disagree — indicating uncertainty that requires human review.
 *
 * @param fieldConsensus     per-field consensus details
 * @param overallConsensus   0.0 (all disagree) to 1.0 (all agree on all fields)
 * @param agreementCount     number of fields where all structurers agree
 * @param disagreementCount  number of fields where structurers disagree
 * @param sourceCount        number of structurers that contributed
 * @param flaggedFields      fields with disagreement — candidates for review
 */
public record ConsensusResult(
        Map<AddressField, FieldConsensus> fieldConsensus,
        double overallConsensus,
        int agreementCount,
        int disagreementCount,
        int sourceCount,
        Set<AddressField> flaggedFields,
        Map<String, Double> sourceWeights) {

    public ConsensusResult {
        Objects.requireNonNull(fieldConsensus, "fieldConsensus");
        Objects.requireNonNull(flaggedFields, "flaggedFields");
        fieldConsensus = Map.copyOf(fieldConsensus);
        flaggedFields = Set.copyOf(flaggedFields);
        sourceWeights = sourceWeights != null ? Map.copyOf(sourceWeights) : Map.of();
    }

    /** Backwards-compatible constructor without weights. */
    public ConsensusResult(Map<AddressField, FieldConsensus> fieldConsensus,
                           double overallConsensus, int agreementCount,
                           int disagreementCount, int sourceCount,
                           Set<AddressField> flaggedFields) {
        this(fieldConsensus, overallConsensus, agreementCount,
                disagreementCount, sourceCount, flaggedFields, Map.of());
    }

    public boolean hasDisagreements() {
        return disagreementCount > 0;
    }

    /**
     * Per-field consensus detail.
     *
     * @param field          the address field
     * @param agreed         whether all structurers producing this field agree
     * @param consensusValue the agreed-upon value (or the highest-confidence value if disagreed)
     * @param alternatives   all different values produced (empty if agreed)
     * @param sources        which structurers contributed to this field
     */
    public record FieldConsensus(
            AddressField field,
            boolean agreed,
            String consensusValue,
            Map<String, String> alternatives,
            Set<String> sources) {

        public FieldConsensus {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(consensusValue, "consensusValue");
            Objects.requireNonNull(alternatives, "alternatives");
            Objects.requireNonNull(sources, "sources");
            alternatives = Map.copyOf(alternatives);
            sources = Set.copyOf(sources);
        }
    }
}

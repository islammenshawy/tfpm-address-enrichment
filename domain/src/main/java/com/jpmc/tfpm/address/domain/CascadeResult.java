package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;

import java.util.List;
import java.util.Objects;

/**
 * Output of the cascade orchestrator: the merged structured address,
 * per-structurer trace, and consensus analysis.
 *
 * @param structuredAddress merged result from FieldMerger
 * @param structurerTrace   results from each structurer that ran (in order)
 * @param overallConfidence calibrated min-across-required-fields, [0.0, 1.0]
 * @param consensus         cross-structurer agreement analysis (null if only 1 source)
 */
public record CascadeResult(
        StructuredAddress structuredAddress,
        List<StructuringResult> structurerTrace,
        double overallConfidence,
        ConsensusResult consensus) {

    public CascadeResult {
        Objects.requireNonNull(structuredAddress, "structuredAddress");
        Objects.requireNonNull(structurerTrace, "structurerTrace");
        structurerTrace = List.copyOf(structurerTrace);
    }

    /** Backward-compatible constructor (no consensus). */
    public CascadeResult(StructuredAddress structuredAddress,
                         List<StructuringResult> structurerTrace,
                         double overallConfidence) {
        this(structuredAddress, structurerTrace, overallConfidence, null);
    }

    public boolean hasConsensusDisagreements() {
        return consensus != null && consensus.hasDisagreements();
    }
}

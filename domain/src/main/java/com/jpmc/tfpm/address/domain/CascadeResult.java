package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.StructuredAddress;

import java.util.List;
import java.util.Objects;

/**
 * Output of the cascade orchestrator: the merged structured address plus
 * the per-structurer trace for audit and diagnostics.
 *
 * @param structuredAddress merged result from FieldMerger
 * @param structurerTrace   results from each structurer that ran (in order)
 * @param overallConfidence calibrated min-across-required-fields, [0.0, 1.0]
 */
public record CascadeResult(
        StructuredAddress structuredAddress,
        List<StructuringResult> structurerTrace,
        double overallConfidence) {

    public CascadeResult {
        Objects.requireNonNull(structuredAddress, "structuredAddress");
        Objects.requireNonNull(structurerTrace, "structurerTrace");
        structurerTrace = List.copyOf(structurerTrace);
    }
}

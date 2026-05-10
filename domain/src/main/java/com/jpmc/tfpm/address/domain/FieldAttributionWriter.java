package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;

import java.util.List;

/**
 * Port for writing per-field structurer attribution to FIELD_ATTRIBUTIONS.
 * One row per (result, field, structurer) tuple. The merger's choice is
 * marked WAS_SELECTED='Y'.
 */
public interface FieldAttributionWriter {

    void writeAttributions(long resultId, List<StructuringResult> trace,
                           StructuredAddress merged, String countryHint);
}

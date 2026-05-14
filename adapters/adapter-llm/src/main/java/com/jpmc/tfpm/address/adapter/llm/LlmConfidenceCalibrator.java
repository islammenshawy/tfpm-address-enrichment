package com.jpmc.tfpm.address.adapter.llm;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.ThreadSafe;

/**
 * Identity calibrator for LLM structurers. Clamps raw confidence to [0,1].
 * Accepts a dynamic name matching the provider key from config.
 */
@ThreadSafe
public final class LlmConfidenceCalibrator implements ConfidenceCalibrator {

    private final String name;

    public LlmConfidenceCalibrator() {
        this("llm");
    }

    public LlmConfidenceCalibrator(String structurerName) {
        this.name = structurerName;
    }

    @Override
    public String structurerName() {
        return name;
    }

    @Override
    public double calibrate(double raw, AddressField field, String countryCode) {
        if (Double.isNaN(raw) || Double.isInfinite(raw)) return 0.0;
        return Math.max(0.0, Math.min(1.0, raw));
    }
}

package com.jpmc.tfpm.address.adapter.libpostal;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.ThreadSafe;

/**
 * Day-1 identity calibrator for libpostal. Clamps raw confidence to [0,1].
 * Real calibration from golden-set analysis replaces this later.
 */
@ThreadSafe
public final class LibpostalConfidenceCalibrator implements ConfidenceCalibrator {

    @Override
    public String structurerName() {
        return "libpostal";
    }

    @Override
    public double calibrate(double raw, AddressField field, String countryCode) {
        if (Double.isNaN(raw) || Double.isInfinite(raw)) return 0.0;
        return Math.max(0.0, Math.min(1.0, raw));
    }
}

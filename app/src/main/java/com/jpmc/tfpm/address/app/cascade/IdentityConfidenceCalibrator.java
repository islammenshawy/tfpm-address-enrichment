package com.jpmc.tfpm.address.app.cascade;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.ThreadSafe;

/**
 * Day-1 identity calibrator: clamps raw confidence to [0.0, 1.0].
 *
 * <p>Real calibration (isotonic regression / Platt scaling per structurer
 * per field per country) replaces this once enough golden-set samples
 * are available.
 */
@ThreadSafe
public final class IdentityConfidenceCalibrator implements ConfidenceCalibrator {

    private final String structurerName;

    public IdentityConfidenceCalibrator(String structurerName) {
        this.structurerName = structurerName;
    }

    @Override
    public String structurerName() {
        return structurerName;
    }

    @Override
    public double calibrate(double raw, AddressField field, String countryCode) {
        if (Double.isNaN(raw) || Double.isInfinite(raw)) return 0.0;
        return Math.max(0.0, Math.min(1.0, raw));
    }
}

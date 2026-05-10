package com.jpmc.tfpm.address.adapter.libpostal;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LibpostalConfidenceCalibrator")
class LibpostalConfidenceCalibratorTest {

    private final LibpostalConfidenceCalibrator calibrator = new LibpostalConfidenceCalibrator();

    @Test
    void structurer_name_matches() {
        assertThat(calibrator.structurerName()).isEqualTo("libpostal");
    }

    @Test
    void clamps_to_zero_one_range() {
        assertThat(calibrator.calibrate(0.5, AddressField.CTRY, "US")).isEqualTo(0.5);
        assertThat(calibrator.calibrate(1.5, AddressField.CTRY, "US")).isEqualTo(1.0);
        assertThat(calibrator.calibrate(-0.5, AddressField.CTRY, "US")).isEqualTo(0.0);
    }

    @Test
    void nan_returns_zero() {
        assertThat(calibrator.calibrate(Double.NaN, AddressField.CTRY, "US")).isEqualTo(0.0);
    }

    @Test
    void infinity_returns_zero() {
        assertThat(calibrator.calibrate(Double.POSITIVE_INFINITY, AddressField.CTRY, "US")).isEqualTo(0.0);
        assertThat(calibrator.calibrate(Double.NEGATIVE_INFINITY, AddressField.CTRY, "US")).isEqualTo(0.0);
    }
}

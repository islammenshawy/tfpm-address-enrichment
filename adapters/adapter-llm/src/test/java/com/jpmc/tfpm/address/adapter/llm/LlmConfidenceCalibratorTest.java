package com.jpmc.tfpm.address.adapter.llm;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LlmConfidenceCalibrator")
class LlmConfidenceCalibratorTest {

    private final LlmConfidenceCalibrator calibrator = new LlmConfidenceCalibrator();

    @Test
    void structurer_name_is_llm() {
        assertThat(calibrator.structurerName()).isEqualTo("llm");
    }

    @Test
    void clamps_to_zero_one() {
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
    }
}

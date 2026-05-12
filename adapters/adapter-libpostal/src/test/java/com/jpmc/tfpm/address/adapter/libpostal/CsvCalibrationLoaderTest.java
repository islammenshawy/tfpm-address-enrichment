package com.jpmc.tfpm.address.adapter.libpostal;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("CsvCalibrationLoader")
class CsvCalibrationLoaderTest {

    @Test
    void structurer_name_matches() {
        var loader = new CsvCalibrationLoader("libpostal", "");
        assertThat(loader.structurerName()).isEqualTo("libpostal");
    }

    @Test
    void empty_resource_uses_identity_calibration() {
        var loader = new CsvCalibrationLoader("libpostal", "");
        assertThat(loader.calibrate(0.75, AddressField.CTRY, "US")).isEqualTo(0.75);
    }

    @Test
    void missing_resource_falls_back_to_identity() {
        var loader = new CsvCalibrationLoader("libpostal", "classpath:nonexistent.csv");
        assertThat(loader.calibrate(0.80, AddressField.TWN_NM, "GB")).isEqualTo(0.80);
    }

    @Test
    void nan_returns_zero() {
        var loader = new CsvCalibrationLoader("libpostal", "");
        assertThat(loader.calibrate(Double.NaN, AddressField.CTRY, "US")).isEqualTo(0.0);
    }

    @Test
    void infinity_returns_zero() {
        var loader = new CsvCalibrationLoader("libpostal", "");
        assertThat(loader.calibrate(Double.POSITIVE_INFINITY, AddressField.CTRY, "US")).isEqualTo(0.0);
    }

    @Test
    void clamps_to_zero_one_range() {
        var loader = new CsvCalibrationLoader("libpostal", "");
        assertThat(loader.calibrate(1.5, AddressField.CTRY, "US")).isEqualTo(1.0);
        assertThat(loader.calibrate(-0.5, AddressField.CTRY, "US")).isEqualTo(0.0);
    }
}

package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AddressStructurer types")
class AddressStructurerTest {

    @Test
    void all_address_fields_present() {
        assertThat(AddressField.values()).containsExactly(
                AddressField.CTRY,
                AddressField.TWN_NM,
                AddressField.PST_CD,
                AddressField.CTRY_SUB_DVSN,
                AddressField.STRT_NM,
                AddressField.BLDG_NB,
                AddressField.BLDG_NM,
                AddressField.ADR_LINE);
    }

    @Test
    void field_value_construction() {
        var fv = new FieldValue("London", 0.95);
        assertThat(fv.value()).isEqualTo("London");
        assertThat(fv.confidence()).isEqualTo(0.95);
    }

    @Test
    void field_value_rejects_null_value() {
        assertThatThrownBy(() -> new FieldValue(null, 0.5))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void structuring_result_construction() {
        var fields = Map.of(
                AddressField.CTRY, new FieldValue("GB", 0.99),
                AddressField.TWN_NM, new FieldValue("London", 0.95));
        var diag = Map.<String, Object>of("model", "libpostal-1.0");

        var result = new StructuringResult("libpostal", fields, Duration.ofMillis(50), diag);

        assertThat(result.structurerName()).isEqualTo("libpostal");
        assertThat(result.fields()).hasSize(2);
        assertThat(result.latency()).isEqualTo(Duration.ofMillis(50));
        assertThat(result.diagnostics()).containsEntry("model", "libpostal-1.0");
    }

    @Test
    void structuring_result_fields_are_defensively_copied() {
        var fields = new HashMap<AddressField, FieldValue>();
        fields.put(AddressField.CTRY, new FieldValue("US", 0.9));

        var result = new StructuringResult("test", fields, Duration.ZERO, Map.of());
        fields.put(AddressField.TWN_NM, new FieldValue("NYC", 0.8));

        assertThat(result.fields()).doesNotContainKey(AddressField.TWN_NM);
    }

    @Test
    void structuring_result_fields_are_immutable() {
        var result = new StructuringResult(
                "test", Map.of(AddressField.CTRY, new FieldValue("US", 0.9)),
                Duration.ZERO, Map.of());
        assertThatThrownBy(() -> result.fields().put(AddressField.TWN_NM, new FieldValue("x", 0.1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void structuring_result_diagnostics_are_defensively_copied() {
        var diag = new HashMap<String, Object>();
        diag.put("key", "val");
        var result = new StructuringResult("test", Map.of(), Duration.ZERO, diag);
        diag.put("added", "after");
        assertThat(result.diagnostics()).doesNotContainKey("added");
    }

    @Test
    void structuring_result_empty_factory() {
        var result = StructuringResult.empty("libpostal", Duration.ofMillis(10));
        assertThat(result.structurerName()).isEqualTo("libpostal");
        assertThat(result.fields()).isEmpty();
        assertThat(result.latency()).isEqualTo(Duration.ofMillis(10));
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void structuring_result_rejects_null_name() {
        assertThatThrownBy(() -> new StructuringResult(null, Map.of(), Duration.ZERO, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void structuring_result_rejects_null_fields() {
        assertThatThrownBy(() -> new StructuringResult("test", null, Duration.ZERO, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void structuring_result_rejects_null_latency() {
        assertThatThrownBy(() -> new StructuringResult("test", Map.of(), null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void structuring_result_rejects_null_diagnostics() {
        assertThatThrownBy(() -> new StructuringResult("test", Map.of(), Duration.ZERO, null))
                .isInstanceOf(NullPointerException.class);
    }
}

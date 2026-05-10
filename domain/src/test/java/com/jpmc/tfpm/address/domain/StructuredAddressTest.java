package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StructuredAddress")
class StructuredAddressTest {

    @Test
    void empty_has_no_fields() {
        var addr = StructuredAddress.empty();
        assertThat(addr.fields()).isEmpty();
    }

    @Test
    void meetsSr2026Minimum_true_when_both_required_present() {
        var addr = StructuredAddress.builder()
                .put(AddressField.CTRY, "US", 0.95)
                .put(AddressField.TWN_NM, "New York", 0.90)
                .build();
        assertThat(addr.meetsSr2026Minimum()).isTrue();
    }

    @Test
    void meetsSr2026Minimum_false_when_ctry_missing() {
        var addr = StructuredAddress.builder()
                .put(AddressField.TWN_NM, "New York", 0.90)
                .build();
        assertThat(addr.meetsSr2026Minimum()).isFalse();
    }

    @Test
    void meetsSr2026Minimum_false_when_twn_nm_missing() {
        var addr = StructuredAddress.builder()
                .put(AddressField.CTRY, "US", 0.95)
                .build();
        assertThat(addr.meetsSr2026Minimum()).isFalse();
    }

    @Test
    void meetsSr2026Minimum_false_when_empty() {
        assertThat(StructuredAddress.empty().meetsSr2026Minimum()).isFalse();
    }

    @Test
    void overallConfidence_is_min_of_required_fields() {
        var addr = StructuredAddress.builder()
                .put(AddressField.CTRY, "US", 0.95)
                .put(AddressField.TWN_NM, "New York", 0.80)
                .build();
        assertThat(addr.overallConfidence()).isEqualTo(0.80);
    }

    @Test
    void overallConfidence_zero_when_required_fields_missing() {
        var addr = StructuredAddress.builder()
                .put(AddressField.CTRY, "US", 0.95)
                .build();
        assertThat(addr.overallConfidence()).isEqualTo(0.0);
    }

    @Test
    void overallConfidence_zero_for_empty() {
        assertThat(StructuredAddress.empty().overallConfidence()).isEqualTo(0.0);
    }

    @Test
    void get_returns_present_field() {
        var addr = StructuredAddress.builder()
                .put(AddressField.PST_CD, "10001", 0.99)
                .build();
        assertThat(addr.get(AddressField.PST_CD)).isPresent();
        assertThat(addr.get(AddressField.PST_CD).get().value()).isEqualTo("10001");
    }

    @Test
    void get_returns_empty_for_missing_field() {
        assertThat(StructuredAddress.empty().get(AddressField.CTRY)).isEmpty();
    }

    @Test
    void builder_builds_with_multiple_fields() {
        var addr = StructuredAddress.builder()
                .put(AddressField.CTRY, "GB", 0.99)
                .put(AddressField.TWN_NM, "London", 0.95)
                .put(AddressField.PST_CD, "SW1A 1AA", 0.88)
                .put(AddressField.STRT_NM, "Downing Street", 0.85)
                .put(AddressField.BLDG_NB, "10", 0.90)
                .build();
        assertThat(addr.fields()).hasSize(5);
    }

    @Test
    void builder_put_with_field_value() {
        var fv = new FieldValue("US", 0.99);
        var addr = StructuredAddress.builder()
                .put(AddressField.CTRY, fv)
                .build();
        assertThat(addr.get(AddressField.CTRY)).contains(fv);
    }

    @Test
    void fields_are_defensively_copied() {
        var fields = new HashMap<AddressField, FieldValue>();
        fields.put(AddressField.CTRY, new FieldValue("US", 0.99));
        var addr = new StructuredAddress(fields);

        fields.put(AddressField.TWN_NM, new FieldValue("NYC", 0.90));
        assertThat(addr.fields()).doesNotContainKey(AddressField.TWN_NM);
    }

    @Test
    void fields_map_is_immutable() {
        var addr = StructuredAddress.builder()
                .put(AddressField.CTRY, "US", 0.99)
                .build();
        assertThatThrownBy(() -> addr.fields().put(AddressField.TWN_NM, new FieldValue("x", 0.5)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejects_null_fields() {
        assertThatThrownBy(() -> new StructuredAddress(null))
                .isInstanceOf(NullPointerException.class);
    }
}

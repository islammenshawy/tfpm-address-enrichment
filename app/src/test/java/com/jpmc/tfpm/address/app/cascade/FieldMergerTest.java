package com.jpmc.tfpm.address.app.cascade;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FieldMerger")
class FieldMergerTest {

    private FieldMerger merger;

    @BeforeEach
    void setUp() {
        merger = new FieldMerger(List.of(
                new IdentityConfidenceCalibrator("libpostal"),
                new IdentityConfidenceCalibrator("llm"),
                new IdentityConfidenceCalibrator("stub")));
    }

    @Test
    void single_result_passes_through() {
        var result = new StructuringResult("libpostal",
                Map.of(AddressField.CTRY, new FieldValue("US", 0.95),
                        AddressField.TWN_NM, new FieldValue("New York", 0.90)),
                Duration.ZERO, Map.of());

        var merged = merger.merge(List.of(result), "US");

        assertThat(merged.get(AddressField.CTRY)).isPresent();
        assertThat(merged.get(AddressField.CTRY).get().value()).isEqualTo("US");
        assertThat(merged.get(AddressField.TWN_NM).get().value()).isEqualTo("New York");
    }

    @Test
    void higher_confidence_wins_per_field() {
        var r1 = new StructuringResult("libpostal",
                Map.of(AddressField.CTRY, new FieldValue("US", 0.80),
                        AddressField.STRT_NM, new FieldValue("Main St", 0.95)),
                Duration.ZERO, Map.of());
        var r2 = new StructuringResult("llm",
                Map.of(AddressField.CTRY, new FieldValue("US", 0.99),
                        AddressField.STRT_NM, new FieldValue("Main Street", 0.70)),
                Duration.ZERO, Map.of());

        var merged = merger.merge(List.of(r1, r2), "US");

        // LLM wins CTRY (0.99 > 0.80)
        assertThat(merged.get(AddressField.CTRY).get().confidence()).isEqualTo(0.99);
        // libpostal wins STRT_NM (0.95 > 0.70)
        assertThat(merged.get(AddressField.STRT_NM).get().value()).isEqualTo("Main St");
        assertThat(merged.get(AddressField.STRT_NM).get().confidence()).isEqualTo(0.95);
    }

    @Test
    void tie_breaking_by_cascade_order() {
        var r1 = new StructuringResult("libpostal",
                Map.of(AddressField.CTRY, new FieldValue("US", 0.90)),
                Duration.ZERO, Map.of());
        var r2 = new StructuringResult("llm",
                Map.of(AddressField.CTRY, new FieldValue("USA-invalid", 0.90)),
                Duration.ZERO, Map.of());

        var merged = merger.merge(List.of(r1, r2), "");

        // Same confidence — first in list (libpostal) wins
        assertThat(merged.get(AddressField.CTRY).get().value()).isEqualTo("US");
    }

    @Test
    void empty_field_values_are_skipped() {
        var r1 = new StructuringResult("libpostal",
                Map.of(AddressField.CTRY, new FieldValue("", 0.95)),
                Duration.ZERO, Map.of());
        var r2 = new StructuringResult("llm",
                Map.of(AddressField.CTRY, new FieldValue("GB", 0.80)),
                Duration.ZERO, Map.of());

        var merged = merger.merge(List.of(r1, r2), "");

        assertThat(merged.get(AddressField.CTRY).get().value()).isEqualTo("GB");
    }

    @Test
    void empty_results_list_returns_empty_address() {
        var merged = merger.merge(List.of(), "US");
        assertThat(merged.fields()).isEmpty();
    }

    @Test
    void fields_from_different_structurers_combined() {
        var r1 = new StructuringResult("libpostal",
                Map.of(AddressField.STRT_NM, new FieldValue("Main St", 0.95)),
                Duration.ZERO, Map.of());
        var r2 = new StructuringResult("llm",
                Map.of(AddressField.CTRY, new FieldValue("US", 0.99),
                        AddressField.TWN_NM, new FieldValue("Springfield", 0.90)),
                Duration.ZERO, Map.of());

        var merged = merger.merge(List.of(r1, r2), "US");

        assertThat(merged.fields()).hasSize(3);
        assertThat(merged.get(AddressField.STRT_NM).get().value()).isEqualTo("Main St");
        assertThat(merged.get(AddressField.CTRY).get().value()).isEqualTo("US");
        assertThat(merged.get(AddressField.TWN_NM).get().value()).isEqualTo("Springfield");
    }

    @Test
    void unknown_structurer_uses_clamped_raw() {
        var mergerNoCalibrator = new FieldMerger(List.of());
        var result = new StructuringResult("unknown",
                Map.of(AddressField.CTRY, new FieldValue("DE", 0.85)),
                Duration.ZERO, Map.of());

        var merged = mergerNoCalibrator.merge(List.of(result), "");
        assertThat(merged.get(AddressField.CTRY).get().value()).isEqualTo("DE");
        assertThat(merged.get(AddressField.CTRY).get().confidence()).isEqualTo(0.85);
    }
}

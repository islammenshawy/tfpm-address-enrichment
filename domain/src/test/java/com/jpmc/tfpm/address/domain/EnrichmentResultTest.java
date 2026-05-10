package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EnrichmentResult")
class EnrichmentResultTest {

    private EnrichmentResult make(EnrichmentResult.Outcome outcome) {
        return new EnrichmentResult(
                "corr-1", outcome, StructuredAddress.empty(), 0.85, 1L, Instant.now());
    }

    @Test
    void isSuccess_true_for_SUCCESS() {
        assertThat(make(EnrichmentResult.Outcome.SUCCESS).isSuccess()).isTrue();
    }

    @Test
    void isSuccess_true_for_PERSISTED_DUPLICATE() {
        assertThat(make(EnrichmentResult.Outcome.PERSISTED_DUPLICATE).isSuccess()).isTrue();
    }

    @Test
    void isSuccess_false_for_REQUIRES_REVIEW() {
        assertThat(make(EnrichmentResult.Outcome.REQUIRES_REVIEW).isSuccess()).isFalse();
    }

    @Test
    void isSuccess_false_for_UNSTRUCTURABLE() {
        assertThat(make(EnrichmentResult.Outcome.UNSTRUCTURABLE).isSuccess()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(EnrichmentResult.Outcome.class)
    void all_outcomes_constructable(EnrichmentResult.Outcome outcome) {
        var result = make(outcome);
        assertThat(result.outcome()).isEqualTo(outcome);
    }

    @Test
    void rejects_null_correlation_id() {
        assertThatThrownBy(() -> new EnrichmentResult(
                null, EnrichmentResult.Outcome.SUCCESS,
                StructuredAddress.empty(), 0.9, 1L, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_null_outcome() {
        assertThatThrownBy(() -> new EnrichmentResult(
                "corr", null, StructuredAddress.empty(), 0.9, 1L, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_null_structured_address() {
        assertThatThrownBy(() -> new EnrichmentResult(
                "corr", EnrichmentResult.Outcome.SUCCESS, null, 0.9, 1L, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_null_processed_at() {
        assertThatThrownBy(() -> new EnrichmentResult(
                "corr", EnrichmentResult.Outcome.SUCCESS,
                StructuredAddress.empty(), 0.9, 1L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void allows_null_result_row_id() {
        var result = new EnrichmentResult(
                "corr", EnrichmentResult.Outcome.UNSTRUCTURABLE,
                StructuredAddress.empty(), 0.0, null, Instant.now());
        assertThat(result.resultRowId()).isNull();
    }
}

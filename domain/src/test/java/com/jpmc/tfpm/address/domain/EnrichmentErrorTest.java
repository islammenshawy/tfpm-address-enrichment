package com.jpmc.tfpm.address.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EnrichmentError")
class EnrichmentErrorTest {

    private static final Set<EnrichmentError.Category> RETRYABLE = Set.of(
            EnrichmentError.Category.TIMEOUT,
            EnrichmentError.Category.NETWORK,
            EnrichmentError.Category.UPSTREAM_RATE_LIMITED,
            EnrichmentError.Category.UPSTREAM_UNAVAILABLE,
            EnrichmentError.Category.DATABASE_DEADLOCK,
            EnrichmentError.Category.DATABASE_CONNECTION);

    @ParameterizedTest
    @EnumSource(EnrichmentError.Category.class)
    void isRetryable_matches_expected(EnrichmentError.Category category) {
        var error = EnrichmentError.of(category, "msg", "corr-1");
        assertThat(error.isRetryable())
                .as("Category %s should be retryable=%s", category, RETRYABLE.contains(category))
                .isEqualTo(RETRYABLE.contains(category));
    }

    @ParameterizedTest
    @EnumSource(EnrichmentError.Category.class)
    void suggestedHttpStatus_is_valid(EnrichmentError.Category category) {
        var error = EnrichmentError.of(category, "msg", "corr-1");
        int status = error.suggestedHttpStatus();
        assertThat(status).as("Category %s", category).isBetween(200, 599);
    }

    @Test
    void of_simple_factory() {
        var error = EnrichmentError.of(
                EnrichmentError.Category.TIMEOUT, "timed out", "corr-1");
        assertThat(error.category()).isEqualTo(EnrichmentError.Category.TIMEOUT);
        assertThat(error.message()).isEqualTo("timed out");
        assertThat(error.correlationId()).isEqualTo("corr-1");
        assertThat(error.context()).isEmpty();
        assertThat(error.cause()).isNull();
        assertThat(error.causeOpt()).isEmpty();
    }

    @Test
    void of_with_cause() {
        var cause = new RuntimeException("boom");
        var error = EnrichmentError.of(
                EnrichmentError.Category.NETWORK, "network err", "corr-2", cause);
        assertThat(error.cause()).isSameAs(cause);
        assertThat(error.causeOpt()).contains(cause);
    }

    @Test
    void full_constructor_with_context() {
        var ctx = Map.<String, Object>of("key", "value");
        var error = new EnrichmentError(
                EnrichmentError.Category.BAD_INPUT, "bad", "corr-3", ctx, null);
        assertThat(error.context()).containsEntry("key", "value");
    }

    @Test
    void context_is_immutable() {
        var ctx = new java.util.HashMap<String, Object>();
        ctx.put("key", "value");
        var error = new EnrichmentError(
                EnrichmentError.Category.BAD_INPUT, "bad", "corr-3", ctx, null);
        ctx.put("another", "value2");
        assertThat(error.context()).doesNotContainKey("another");
        assertThatThrownBy(() -> error.context().put("new", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejects_null_category() {
        assertThatThrownBy(() -> EnrichmentError.of(null, "msg", "corr"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_null_message() {
        assertThatThrownBy(() -> EnrichmentError.of(EnrichmentError.Category.TIMEOUT, null, "corr"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_null_correlation_id() {
        assertThatThrownBy(() -> EnrichmentError.of(EnrichmentError.Category.TIMEOUT, "msg", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void specific_http_status_codes() {
        assertThat(EnrichmentError.of(EnrichmentError.Category.BAD_INPUT, "m", "c").suggestedHttpStatus()).isEqualTo(400);
        assertThat(EnrichmentError.of(EnrichmentError.Category.UNAUTHORISED, "m", "c").suggestedHttpStatus()).isEqualTo(401);
        assertThat(EnrichmentError.of(EnrichmentError.Category.FORBIDDEN, "m", "c").suggestedHttpStatus()).isEqualTo(403);
        assertThat(EnrichmentError.of(EnrichmentError.Category.IDEMPOTENCY_DUPLICATE, "m", "c").suggestedHttpStatus()).isEqualTo(200);
        assertThat(EnrichmentError.of(EnrichmentError.Category.TIMEOUT, "m", "c").suggestedHttpStatus()).isEqualTo(504);
        assertThat(EnrichmentError.of(EnrichmentError.Category.CIRCUIT_OPEN, "m", "c").suggestedHttpStatus()).isEqualTo(503);
        assertThat(EnrichmentError.of(EnrichmentError.Category.NETWORK, "m", "c").suggestedHttpStatus()).isEqualTo(500);
    }
}

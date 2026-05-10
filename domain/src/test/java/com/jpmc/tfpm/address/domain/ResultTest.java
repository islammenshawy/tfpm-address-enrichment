package com.jpmc.tfpm.address.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Result<T> sealed type")
class ResultTest {

    private static final EnrichmentError SAMPLE_ERROR = EnrichmentError.of(
            EnrichmentError.Category.TIMEOUT, "timed out", "corr-1");

    @Nested
    @DisplayName("Success")
    class SuccessTests {

        @Test
        void success_holds_value() {
            var result = Result.success("hello");
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isFailure()).isFalse();
            assertThat(result.toOptionalValue()).contains("hello");
            assertThat(result.toOptionalError()).isEmpty();
        }

        @Test
        void success_allows_null_value() {
            var result = Result.success(null);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.toOptionalValue()).isEmpty();
        }

        @Test
        void getOrElse_returns_value() {
            assertThat(Result.success("a").getOrElse("b")).isEqualTo("a");
        }

        @Test
        void getOrThrow_returns_value() {
            assertThat(Result.success("a").getOrThrow()).isEqualTo("a");
        }
    }

    @Nested
    @DisplayName("Failure")
    class FailureTests {

        @Test
        void failure_holds_error() {
            var result = Result.<String>failure(SAMPLE_ERROR);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isFailure()).isTrue();
            assertThat(result.toOptionalValue()).isEmpty();
            assertThat(result.toOptionalError()).contains(SAMPLE_ERROR);
        }

        @Test
        void failure_rejects_null_error() {
            assertThatThrownBy(() -> Result.failure(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void getOrElse_returns_fallback() {
            assertThat(Result.<String>failure(SAMPLE_ERROR).getOrElse("fallback"))
                    .isEqualTo("fallback");
        }

        @Test
        void getOrThrow_throws() {
            assertThatThrownBy(() -> Result.<String>failure(SAMPLE_ERROR).getOrThrow())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("getOrThrow on Failure");
        }
    }

    @Nested
    @DisplayName("map")
    class MapTests {

        @Test
        void map_transforms_success() {
            var result = Result.success("hello").map(String::toUpperCase);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.toOptionalValue()).contains("HELLO");
        }

        @Test
        void map_passes_through_failure() {
            var result = Result.<String>failure(SAMPLE_ERROR).map(String::toUpperCase);
            assertThat(result.isFailure()).isTrue();
            assertThat(result.toOptionalError()).contains(SAMPLE_ERROR);
        }

        @Test
        void map_rejects_null_mapper() {
            assertThatThrownBy(() -> Result.success("a").map(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("flatMap")
    class FlatMapTests {

        @Test
        void flatMap_chains_success() {
            var result = Result.success("hello")
                    .flatMap(s -> Result.success(s.length()));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.toOptionalValue()).contains(5);
        }

        @Test
        void flatMap_chains_to_failure() {
            var result = Result.success("hello")
                    .flatMap(s -> Result.<Integer>failure(SAMPLE_ERROR));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void flatMap_short_circuits_failure() {
            var called = new AtomicBoolean(false);
            var result = Result.<String>failure(SAMPLE_ERROR)
                    .flatMap(s -> {
                        called.set(true);
                        return Result.success(s.length());
                    });
            assertThat(result.isFailure()).isTrue();
            assertThat(called).isFalse();
        }

        @Test
        void flatMap_rejects_null_mapper() {
            assertThatThrownBy(() -> Result.success("a").flatMap(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("recover / recoverWith")
    class RecoverTests {

        @Test
        void recover_maps_failure_to_success() {
            var result = Result.<String>failure(SAMPLE_ERROR)
                    .recover(err -> "recovered");
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.toOptionalValue()).contains("recovered");
        }

        @Test
        void recover_is_noop_on_success() {
            var result = Result.success("original")
                    .recover(err -> "recovered");
            assertThat(result.toOptionalValue()).contains("original");
        }

        @Test
        void recoverWith_chains_recovery() {
            var result = Result.<String>failure(SAMPLE_ERROR)
                    .recoverWith(err -> Result.success("recovered"));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.toOptionalValue()).contains("recovered");
        }

        @Test
        void recoverWith_can_fail_again() {
            var otherError = EnrichmentError.of(
                    EnrichmentError.Category.NETWORK, "net err", "corr-2");
            var result = Result.<String>failure(SAMPLE_ERROR)
                    .recoverWith(err -> Result.failure(otherError));
            assertThat(result.isFailure()).isTrue();
            assertThat(result.toOptionalError()).contains(otherError);
        }

        @Test
        void recoverWith_is_noop_on_success() {
            var result = Result.success("original")
                    .recoverWith(err -> Result.success("recovered"));
            assertThat(result.toOptionalValue()).contains("original");
        }
    }

    @Nested
    @DisplayName("ifSuccess / ifFailure")
    class SideEffectTests {

        @Test
        void ifSuccess_executes_on_success() {
            var called = new AtomicBoolean(false);
            var result = Result.success("hello").ifSuccess(v -> called.set(true));
            assertThat(called).isTrue();
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void ifSuccess_skips_on_failure() {
            var called = new AtomicBoolean(false);
            Result.<String>failure(SAMPLE_ERROR).ifSuccess(v -> called.set(true));
            assertThat(called).isFalse();
        }

        @Test
        void ifFailure_executes_on_failure() {
            var called = new AtomicBoolean(false);
            var result = Result.<String>failure(SAMPLE_ERROR).ifFailure(e -> called.set(true));
            assertThat(called).isTrue();
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void ifFailure_skips_on_success() {
            var called = new AtomicBoolean(false);
            Result.success("hello").ifFailure(e -> called.set(true));
            assertThat(called).isFalse();
        }
    }

    @Nested
    @DisplayName("pattern matching")
    class PatternMatchingTests {

        @Test
        void switch_on_success() {
            var result = Result.success("hello");
            String out = switch (result) {
                case Result.Success<String>(var v) -> "got: " + v;
                case Result.Failure<String> f -> "fail";
            };
            assertThat(out).isEqualTo("got: hello");
        }

        @Test
        void switch_on_failure() {
            var result = Result.<String>failure(SAMPLE_ERROR);
            String out = switch (result) {
                case Result.Success<String>(var v) -> "got: " + v;
                case Result.Failure<String>(var err) -> "fail: " + err.category();
            };
            assertThat(out).isEqualTo("fail: TIMEOUT");
        }
    }
}

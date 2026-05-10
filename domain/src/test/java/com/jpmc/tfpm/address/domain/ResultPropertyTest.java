package com.jpmc.tfpm.address.domain;

import net.jqwik.api.*;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@Label("Result<T> property-based tests")
class ResultPropertyTest {

    private static final EnrichmentError ERROR = EnrichmentError.of(
            EnrichmentError.Category.TIMEOUT, "timeout", "corr");

    @Property
    void map_identity_law(@ForAll String value) {
        var result = Result.success(value);
        var mapped = result.map(Function.identity());
        assertThat(mapped.toOptionalValue()).isEqualTo(result.toOptionalValue());
    }

    @Property
    void success_map_always_success(@ForAll String value) {
        var result = Result.success(value).map(s -> s + "!");
        assertThat(result.isSuccess()).isTrue();
    }

    @Property
    void failure_map_always_failure(@ForAll String ignored) {
        var result = Result.<String>failure(ERROR).map(s -> s + "!");
        assertThat(result.isFailure()).isTrue();
        assertThat(result.toOptionalError()).contains(ERROR);
    }

    @Property
    void flatMap_associativity(@ForAll int value) {
        Function<Integer, Result<String>> f = i -> Result.success(String.valueOf(i));
        Function<String, Result<Integer>> g = s -> Result.success(s.length());

        var left = Result.success(value).flatMap(f).flatMap(g);
        var right = Result.success(value).flatMap(v -> f.apply(v).flatMap(g));

        assertThat(left.toOptionalValue()).isEqualTo(right.toOptionalValue());
    }

    @Property
    void success_getOrElse_returns_value(@ForAll String value) {
        assertThat(Result.success(value).getOrElse("fallback")).isEqualTo(value);
    }

    @Property
    void failure_getOrElse_returns_fallback(@ForAll String fallback) {
        assertThat(Result.<String>failure(ERROR).getOrElse(fallback)).isEqualTo(fallback);
    }

    @Property
    void recover_on_failure_produces_success(@ForAll String recovery) {
        var result = Result.<String>failure(ERROR).recover(err -> recovery);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.toOptionalValue()).contains(recovery);
    }

    @Property
    void recover_on_success_is_noop(@ForAll String value) {
        var result = Result.success(value).recover(err -> "recovered");
        assertThat(result.toOptionalValue()).contains(value);
    }
}

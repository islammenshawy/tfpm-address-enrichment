package com.jpmc.tfpm.address.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Uniform success/failure return shape used at every layer of the system.
 *
 * <p>Every operation that can fail in ways the caller might want to react
 * to (instead of crashing) returns a {@code Result<T>}. Programmer errors
 * (null where forbidden, illegal argument) still throw; only domain and
 * infrastructure failures use {@code Result}.
 *
 * <p>See {@code docs/RETRY_AND_RESULT.md} for the full rationale,
 * including which categories of failure return {@code Result} vs throw.
 *
 * <h2>Pattern matching</h2>
 *
 * <pre>{@code
 * return switch (idempotencyStore.tryClaim(req)) {
 *     case Result.Success<ClaimResult>(var claim) when claim.isClaimed() ->
 *         runCascadeAndPersist(req);
 *     case Result.Success<ClaimResult>(var claim) ->
 *         loadCachedResult(claim.idempotencyKey());
 *     case Result.Failure<ClaimResult>(var error) ->
 *         Result.failure(error);
 * };
 * }</pre>
 *
 * <h2>Composition</h2>
 *
 * <p>Use {@link #map(Function)} for pure transformations and
 * {@link #flatMap(Function)} for chaining further fallible operations:
 *
 * <pre>{@code
 * return llmClient.complete(req)
 *     .flatMap(this::parseResponse)
 *     .map(this::toStructuringResult);
 * }</pre>
 */
public sealed interface Result<T> permits Result.Success, Result.Failure {

    record Success<T>(T value) implements Result<T> {
        public Success {
            // null is a legitimate Success value for Result<Optional<X>> patterns,
            // so do not Objects.requireNonNull here. Callers that need non-null
            // should validate their own values before constructing.
        }

        @Override public boolean isSuccess() { return true; }
        @Override public Optional<T> toOptionalValue() { return Optional.ofNullable(value); }
        @Override public Optional<EnrichmentError> toOptionalError() { return Optional.empty(); }
    }

    record Failure<T>(EnrichmentError error) implements Result<T> {
        public Failure {
            Objects.requireNonNull(error, "error");
        }

        @Override public boolean isSuccess() { return false; }
        @Override public Optional<T> toOptionalValue() { return Optional.empty(); }
        @Override public Optional<EnrichmentError> toOptionalError() { return Optional.of(error); }
    }

    static <T> Result<T> success(T value) {
        return new Success<>(value);
    }

    static <T> Result<T> failure(EnrichmentError error) {
        return new Failure<>(error);
    }

    boolean isSuccess();
    Optional<T> toOptionalValue();
    Optional<EnrichmentError> toOptionalError();

    default boolean isFailure() {
        return !isSuccess();
    }

    /**
     * Transform a successful value. No-op on failure.
     */
    @SuppressWarnings("unchecked")
    default <U> Result<U> map(Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return switch (this) {
            case Success<T>(var v) -> success(mapper.apply(v));
            case Failure<T> f -> (Result<U>) f;
        };
    }

    /**
     * Chain another fallible operation. No-op on failure.
     */
    @SuppressWarnings("unchecked")
    default <U> Result<U> flatMap(Function<? super T, ? extends Result<U>> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return switch (this) {
            case Success<T>(var v) -> (Result<U>) mapper.apply(v);
            case Failure<T> f -> (Result<U>) f;
        };
    }

    /**
     * Recover from a failure by mapping the error to a fallback value.
     * No-op on success.
     */
    default Result<T> recover(Function<? super EnrichmentError, ? extends T> recovery) {
        Objects.requireNonNull(recovery, "recovery");
        return switch (this) {
            case Success<T> s -> s;
            case Failure<T>(var error) -> success(recovery.apply(error));
        };
    }

    /**
     * Recover from a failure with another fallible operation.
     */
    default Result<T> recoverWith(Function<? super EnrichmentError, ? extends Result<T>> recovery) {
        Objects.requireNonNull(recovery, "recovery");
        return switch (this) {
            case Success<T> s -> s;
            case Failure<T>(var error) -> recovery.apply(error);
        };
    }

    /**
     * Side-effect on success. Useful for logging or metrics. Returns {@code this}.
     */
    default Result<T> ifSuccess(Consumer<? super T> action) {
        Objects.requireNonNull(action, "action");
        if (this instanceof Success<T>(var v)) {
            action.accept(v);
        }
        return this;
    }

    /**
     * Side-effect on failure. Useful for logging or metrics. Returns {@code this}.
     */
    default Result<T> ifFailure(Consumer<? super EnrichmentError> action) {
        Objects.requireNonNull(action, "action");
        if (this instanceof Failure<T>(var e)) {
            action.accept(e);
        }
        return this;
    }

    /**
     * Get the value or a fallback. Use sparingly; prefer pattern matching.
     */
    default T getOrElse(T fallback) {
        return this instanceof Success<T>(var v) ? v : fallback;
    }

    /**
     * Get the value or throw. Use only when failure is genuinely impossible
     * given prior validation; prefer pattern matching.
     */
    default T getOrThrow() {
        return switch (this) {
            case Success<T>(var v) -> v;
            case Failure<T>(var error) -> throw new IllegalStateException(
                    "getOrThrow on Failure: " + error);
        };
    }
}

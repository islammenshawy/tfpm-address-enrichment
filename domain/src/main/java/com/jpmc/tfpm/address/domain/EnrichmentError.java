package com.jpmc.tfpm.address.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The closed taxonomy of failure categories used in {@link Result.Failure}.
 *
 * <p>{@link #isRetryable()} is the single source of truth for whether a
 * retry layer should reattempt the call. Adding a new category requires
 * deciding which side of that fence it falls on.
 *
 * <p>See {@code docs/RETRY_AND_RESULT.md} for the layer-by-layer retry
 * semantics this taxonomy enables.
 *
 * @param category      the broad classification of failure
 * @param message       human-readable description; never null, may be empty
 * @param correlationId the correlation id of the request that failed,
 *                      for cross-layer log correlation
 * @param context       free-form structured metadata for diagnostics;
 *                      never null, may be empty; immutable
 * @param cause         the underlying exception if any; may be null
 */
public record EnrichmentError(
        Category category,
        String message,
        String correlationId,
        Map<String, Object> context,
        Throwable cause) {

    public EnrichmentError {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(context, "context");
        context = Map.copyOf(context);
    }

    public static EnrichmentError of(Category category, String message, String correlationId) {
        return new EnrichmentError(category, message, correlationId, Map.of(), null);
    }

    public static EnrichmentError of(
            Category category,
            String message,
            String correlationId,
            Throwable cause) {
        return new EnrichmentError(category, message, correlationId, Map.of(), cause);
    }

    public Optional<Throwable> causeOpt() {
        return Optional.ofNullable(cause);
    }

    /**
     * Whether retry layers should reattempt on this error category.
     * The single decision point for retry policy across the entire stack.
     */
    public boolean isRetryable() {
        return switch (category) {
            // Transient infrastructure failures: retry may help
            case TIMEOUT,
                 NETWORK,
                 UPSTREAM_RATE_LIMITED,
                 UPSTREAM_UNAVAILABLE,
                 DATABASE_DEADLOCK,
                 DATABASE_CONNECTION -> true;

            // Permanent failures: retry won't help
            case BAD_INPUT,
                 UNAUTHORISED,
                 FORBIDDEN,
                 SCHEMA_MISMATCH,
                 UNSUPPORTED_OPERATION,
                 VALIDATION,
                 CASCADE_NO_RESULT,
                 CONFIDENCE_BELOW_THRESHOLD,
                 REQUIRED_FIELD_MISSING,
                 IDEMPOTENCY_DUPLICATE,
                 EXCEPTION_QUEUE_LOCKED,
                 CIRCUIT_OPEN,
                 BULKHEAD_FULL,
                 SUBSCRIBER_OVERFLOW,
                 UNKNOWN -> false;
        };
    }

    /**
     * Suggested HTTP status code if this error reaches the HTTP boundary.
     * Channel adapters use this in their {@code Result -> response} mapping.
     */
    public int suggestedHttpStatus() {
        return switch (category) {
            case BAD_INPUT, VALIDATION -> 400;
            case UNAUTHORISED -> 401;
            case FORBIDDEN -> 403;
            case IDEMPOTENCY_DUPLICATE -> 200; // cached result returned to caller
            case CASCADE_NO_RESULT, CONFIDENCE_BELOW_THRESHOLD,
                 REQUIRED_FIELD_MISSING, SCHEMA_MISMATCH,
                 UNSUPPORTED_OPERATION -> 422;
            case CIRCUIT_OPEN, BULKHEAD_FULL, UPSTREAM_UNAVAILABLE,
                 UPSTREAM_RATE_LIMITED, EXCEPTION_QUEUE_LOCKED -> 503;
            case TIMEOUT -> 504;
            case NETWORK, DATABASE_CONNECTION, DATABASE_DEADLOCK,
                 SUBSCRIBER_OVERFLOW, UNKNOWN -> 500;
        };
    }

    public enum Category {
        // ======================================================
        // Transient — retry may help (isRetryable() == true)
        // ======================================================
        TIMEOUT,
        NETWORK,
        UPSTREAM_RATE_LIMITED,
        UPSTREAM_UNAVAILABLE,
        DATABASE_DEADLOCK,
        DATABASE_CONNECTION,

        // ======================================================
        // Permanent — retry won't help
        // ======================================================
        BAD_INPUT,
        UNAUTHORISED,
        FORBIDDEN,
        SCHEMA_MISMATCH,
        UNSUPPORTED_OPERATION,
        VALIDATION,

        // ======================================================
        // Domain
        // ======================================================
        /** Cascade ran but no structurer produced a usable field. */
        CASCADE_NO_RESULT,
        /** Confidence below review threshold — sent to exception queue. */
        CONFIDENCE_BELOW_THRESHOLD,
        /** Cascade completed but a SR2026-mandatory field is missing. */
        REQUIRED_FIELD_MISSING,
        /** Idempotency key already processed — caller should use cached result. */
        IDEMPOTENCY_DUPLICATE,
        /** Tried to claim an exception that another maker has locked. */
        EXCEPTION_QUEUE_LOCKED,

        // ======================================================
        // Resilience
        // ======================================================
        CIRCUIT_OPEN,
        BULKHEAD_FULL,
        SUBSCRIBER_OVERFLOW,

        // ======================================================
        // Catch-all — always non-retryable; investigate every occurrence
        // ======================================================
        UNKNOWN
    }
}

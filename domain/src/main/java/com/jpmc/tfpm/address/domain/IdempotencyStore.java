package com.jpmc.tfpm.address.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Cross-replica idempotency: ensures the same enrichment request is
 * processed exactly once across all replicas, regardless of which
 * channel(s) delivered it or how many times.
 *
 * <p>Implementations MUST be {@code @ThreadSafe} and MUST use the
 * INSERT-first-catch-ORA-00001 pattern (never SELECT-then-INSERT, which
 * has a race window between SELECT and INSERT during which two replicas
 * can both decide they should process).
 *
 * <p>The implementation in {@code adapter-oracle-app} uses Oracle's
 * unique-constraint enforcement on {@code IDEMPOTENCY_KEYS.IDEM_KEY}
 * to make this race-free at the database level.
 */
public interface IdempotencyStore {

    /**
     * Attempt to claim processing of this request.
     *
     * <p>Computes the idempotency key as
     * {@code SHA-256(request.address.canonical() || request.sourceChannel)},
     * then attempts to INSERT into {@code IDEMPOTENCY_KEYS}.
     *
     * @return {@link ClaimResult#claimed(String)} if this caller now owns
     *         processing — proceed to run the cascade and call
     *         {@link #recordResult(String, long)} when done.
     *         {@link ClaimResult#duplicate(String)} if the key is already
     *         present — call {@link #findCachedResultRowId(String)} to get
     *         the prior result.
     */
    ClaimResult tryClaim(EnrichmentRequest request);

    /**
     * Persist the result row id against the idempotency key.
     * Called once after a successful cascade + persist.
     *
     * @param idempotencyKey from {@link ClaimResult#idempotencyKey()}
     * @param resultRowId    primary key in {@code STRUCTURING_RESULTS}
     */
    void recordResult(String idempotencyKey, long resultRowId);

    /**
     * For a duplicate claim, fetch the result id of the prior processing.
     *
     * <p>If the result is not yet visible (the winning replica is mid-cascade),
     * the implementation polls briefly with bounded backoff. Returns empty
     * only after the bounded retry expires.
     */
    Optional<Long> findCachedResultRowId(String idempotencyKey);

    /**
     * Outcome of a {@link #tryClaim(EnrichmentRequest)} call.
     *
     * @param status          CLAIMED if this caller won the race, DUPLICATE otherwise
     * @param idempotencyKey  the SHA-256 hex key, returned for downstream calls
     */
    record ClaimResult(Status status, String idempotencyKey) {
        public ClaimResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        }

        public static ClaimResult claimed(String key) {
            return new ClaimResult(Status.CLAIMED, key);
        }

        public static ClaimResult duplicate(String key) {
            return new ClaimResult(Status.DUPLICATE, key);
        }

        public boolean isClaimed() {
            return status == Status.CLAIMED;
        }

        public enum Status {
            CLAIMED,
            DUPLICATE
        }
    }
}

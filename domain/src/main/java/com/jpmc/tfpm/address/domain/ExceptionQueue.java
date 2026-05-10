package com.jpmc.tfpm.address.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Port for the maker/checker exception queue. Makers claim open exceptions
 * for review; checkers (or the system) resolve them.
 *
 * <p>Claim uses {@code SELECT ... FOR UPDATE SKIP LOCKED} so multiple
 * makers can work concurrently without blocking each other.
 *
 * <p>Resolve uses optimistic locking via the VERSION column to prevent
 * stale writes.
 */
public interface ExceptionQueue {

    /**
     * Claim up to {@code batchSize} open exceptions for the given operator.
     * Returns a disjoint set from any other concurrent claim call.
     */
    List<ExceptionItem> claim(int batchSize, String claimedBy);

    /**
     * Resolve a previously claimed exception. Fails if the version has changed
     * since the claim (optimistic lock).
     *
     * @return true if resolved, false if version mismatch (stale)
     */
    boolean resolve(long exceptionId, String resolvedBy, String resolutionJson, int expectedVersion);

    record ExceptionItem(
            long exceptionId,
            long resultId,
            String reason,
            String status,
            int version,
            Instant createdAt) {
        public ExceptionItem {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}

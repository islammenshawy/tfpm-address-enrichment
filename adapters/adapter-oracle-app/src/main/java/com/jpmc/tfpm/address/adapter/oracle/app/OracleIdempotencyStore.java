package com.jpmc.tfpm.address.adapter.oracle.app;

import com.jpmc.tfpm.address.domain.EnrichmentError;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.IdempotencyStore;
import com.jpmc.tfpm.address.domain.Result;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

import static org.jooq.impl.DSL.*;

/**
 * Oracle-backed idempotency store using INSERT-first-catch-ORA-00001.
 */
@ThreadSafe
public class OracleIdempotencyStore implements IdempotencyStore {

    private static final Logger LOG = LoggerFactory.getLogger(OracleIdempotencyStore.class);
    private static final int MAX_RESULT_POLL_ATTEMPTS = 3;
    private static final long POLL_INTERVAL_MS = 50;
    private static final MessageDigest SHA256_PROTOTYPE;
    static {
        try {
            SHA256_PROTOTYPE = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final DSLContext dsl;

    public OracleIdempotencyStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Retryable(retryFor = {TransientDataAccessException.class, org.jooq.exception.DataAccessException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public Result<ClaimResult> tryClaim(EnrichmentRequest request) {
        var key = computeKey(request);
        try {
            dsl.insertInto(table("IDEMPOTENCY_KEYS"))
                    .columns(
                            field("IDEM_KEY"),
                            field("SOURCE_CHANNEL"),
                            field("SOURCE_REF"),
                            field("PROCESSED_AT"))
                    .values(
                            key,
                            request.sourceChannel().name(),
                            request.correlationId(),
                            LocalDateTime.now())
                    .execute();
            LOG.debug("Claimed idempotency key={} [corrId={}]", key, request.correlationId());
            return Result.success(ClaimResult.claimed(key));
        } catch (DataAccessException e) {
            if (isDuplicateKeyError(e)) {
                LOG.debug("Duplicate idempotency key={} [corrId={}]", key, request.correlationId());
                return Result.success(ClaimResult.duplicate(key));
            }
            LOG.error("Failed to claim idempotency key={} [corrId={}]", key, request.correlationId(), e);
            return Result.failure(EnrichmentError.of(
                    EnrichmentError.Category.DATABASE_CONNECTION,
                    "Failed to claim idempotency key: " + e.getMessage(),
                    request.correlationId(), e));
        }
    }

    @Override
    @Retryable(retryFor = {TransientDataAccessException.class, org.jooq.exception.DataAccessException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public Result<Void> recordResult(String idempotencyKey, long resultRowId) {
        try {
            dsl.update(table("IDEMPOTENCY_KEYS"))
                    .set(field("RESULT_REF"), resultRowId)
                    .where(field("IDEM_KEY").eq(idempotencyKey))
                    .execute();
            return Result.success(null);
        } catch (DataAccessException e) {
            LOG.error("Failed to record result for key={}", idempotencyKey, e);
            return Result.failure(EnrichmentError.of(
                    EnrichmentError.Category.DATABASE_CONNECTION,
                    "Failed to record idempotency result: " + e.getMessage(),
                    idempotencyKey, e));
        }
    }

    @Override
    public Optional<Long> findCachedResultRowId(String idempotencyKey) {
        for (int attempt = 0; attempt < MAX_RESULT_POLL_ATTEMPTS; attempt++) {
            var resultRef = dsl.select(field("RESULT_REF"))
                    .from(table("IDEMPOTENCY_KEYS"))
                    .where(field("IDEM_KEY").eq(idempotencyKey))
                    .fetchOne(field("RESULT_REF"), Long.class);

            if (resultRef != null) {
                return Optional.of(resultRef);
            }

            if (attempt < MAX_RESULT_POLL_ATTEMPTS - 1) {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        POLL_INTERVAL_MS * (attempt + 1) * 1_000_000L);
                if (Thread.currentThread().isInterrupted()) {
                    return Optional.empty();
                }
            }
        }
        LOG.warn("Result not visible after {} poll attempts for key={}",
                MAX_RESULT_POLL_ATTEMPTS, idempotencyKey);
        return Optional.empty();
    }

    static String computeKey(EnrichmentRequest request) {
        // Key is computed from canonical business identity only: raw address + country hint.
        // Source channel is deliberately excluded so the same address produces exactly one
        // result row regardless of which channel (HTTP, Kafka, RabbitMQ) delivered it.
        var canonical = request.address().canonical() + "|" + request.address().countryHint();
        try {
            var digest = (MessageDigest) SHA256_PROTOTYPE.clone();
            var hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("SHA-256 clone failed", e);
        }
    }

    private static boolean isDuplicateKeyError(DataAccessException e) {
        var msg = e.getMessage();
        return msg != null && (msg.contains("ORA-00001") || msg.contains("unique constraint")
                || msg.contains("Unique index or primary key violation")
                || msg.contains("IDEMPOTENCY_KEYS"));
    }
}

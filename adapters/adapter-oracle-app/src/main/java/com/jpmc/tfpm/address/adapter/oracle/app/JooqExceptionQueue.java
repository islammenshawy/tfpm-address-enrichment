package com.jpmc.tfpm.address.adapter.oracle.app;

import com.jpmc.tfpm.address.domain.ExceptionQueue;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.jooq.impl.DSL.*;

/**
 * Oracle-backed exception queue using {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * for concurrent claim and optimistic locking for resolve.
 */
@ThreadSafe
public final class JooqExceptionQueue implements ExceptionQueue {

    private static final Logger LOG = LoggerFactory.getLogger(JooqExceptionQueue.class);

    private final DSLContext dsl;

    public JooqExceptionQueue(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Retryable(retryFor = TransientDataAccessException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public List<ExceptionItem> claim(int batchSize, String claimedBy) {
        return dsl.transactionResult(config -> {
            var ctx = config.dsl();

            // SELECT ... FOR UPDATE SKIP LOCKED — concurrent makers get disjoint sets
            var rows = ctx.select(
                            field("EXCEPTION_ID"),
                            field("RESULT_ID"),
                            field("REASON"),
                            field("STATUS"),
                            field("VERSION"),
                            field("CREATED_AT"))
                    .from(table("EXCEPTION_QUEUE"))
                    .where(field("STATUS").eq("OPEN"))
                    .orderBy(field("CREATED_AT").asc())
                    .limit(batchSize)
                    .forUpdate()
                    .skipLocked()
                    .fetch();

            if (rows.isEmpty()) {
                return List.of();
            }

            var ids = rows.map(r -> r.get(field("EXCEPTION_ID", Long.class)));

            // Update claimed rows
            ctx.update(table("EXCEPTION_QUEUE"))
                    .set(field("STATUS"), "CLAIMED")
                    .set(field("CLAIMED_BY"), claimedBy)
                    .set(field("CLAIMED_AT"), LocalDateTime.now())
                    .set(field("VERSION"), field("VERSION", Integer.class).plus(1))
                    .where(field("EXCEPTION_ID").in(ids))
                    .execute();

            LOG.info("Claimed {} exceptions for {}", ids.size(), claimedBy);

            return rows.map(r -> new ExceptionItem(
                    r.get(field("EXCEPTION_ID", Long.class)),
                    r.get(field("RESULT_ID", Long.class)),
                    r.get(field("REASON", String.class)),
                    "CLAIMED",
                    r.get(field("VERSION", Integer.class)) + 1,
                    r.get(field("CREATED_AT", LocalDateTime.class))
                            .atZone(java.time.ZoneId.systemDefault()).toInstant()));
        });
    }

    @Override
    @Retryable(retryFor = TransientDataAccessException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public boolean resolve(long exceptionId, String resolvedBy, String resolutionJson, int expectedVersion) {
        var updated = dsl.update(table("EXCEPTION_QUEUE"))
                .set(field("STATUS"), "RESOLVED")
                .set(field("RESOLVED_BY"), resolvedBy)
                .set(field("RESOLVED_AT"), LocalDateTime.now())
                .set(field("RESOLUTION_JSON"), resolutionJson)
                .set(field("VERSION"), expectedVersion + 1)
                .where(field("EXCEPTION_ID").eq(exceptionId))
                .and(field("VERSION").eq(expectedVersion))
                .and(field("STATUS").eq("CLAIMED"))
                .execute();

        if (updated == 0) {
            LOG.warn("Optimistic lock failed for exceptionId={} expectedVersion={}",
                    exceptionId, expectedVersion);
            return false;
        }

        LOG.info("Resolved exceptionId={} by {}", exceptionId, resolvedBy);
        return true;
    }
}

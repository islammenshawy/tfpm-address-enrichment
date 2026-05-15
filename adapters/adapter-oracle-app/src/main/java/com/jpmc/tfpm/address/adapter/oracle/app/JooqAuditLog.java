package com.jpmc.tfpm.address.adapter.oracle.app;

import com.jpmc.tfpm.address.domain.AuditLog;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

import static org.jooq.impl.DSL.*;

/**
 * Oracle-backed append-only audit log. Writes to TFPM_ADDR_ENRICH.AUDIT_LOG.
 * Never throws — failures are logged and swallowed so audit issues
 * don't disrupt the enrichment pipeline.
 */
@ThreadSafe
public class JooqAuditLog implements AuditLog {

    private static final Logger LOG = LoggerFactory.getLogger(JooqAuditLog.class);

    private final DSLContext dsl;

    public JooqAuditLog(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void record(AuditEvent event) {
        try {
            dsl.insertInto(table("AUDIT_LOG"))
                    .columns(
                            field("EVENT_TIME"),
                            field("EVENT_TYPE"),
                            field("ACTOR"),
                            field("ENTITY_TYPE"),
                            field("ENTITY_ID"),
                            field("DETAILS_JSON"))
                    .values(
                            LocalDateTime.now(),
                            event.eventType(),
                            event.actor(),
                            event.entityType(),
                            event.entityId(),
                            event.detailsJson())
                    .execute();
            LOG.debug("Audit: type={} entity={}:{}", event.eventType(),
                    event.entityType(), event.entityId());
        } catch (Exception e) {
            LOG.error("Failed to write audit event: type={} entity={}:{}",
                    event.eventType(), event.entityType(), event.entityId(), e);
        }
    }
}

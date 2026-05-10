package com.jpmc.tfpm.address.domain;

import java.util.Objects;

/**
 * Port for the append-only audit log. Every enrichment event, exception
 * claim, and resolution is recorded.
 */
public interface AuditLog {

    /**
     * Record an audit event. Never throws — failures are logged and swallowed.
     */
    void record(AuditEvent event);

    record AuditEvent(
            String eventType,
            String actor,
            String entityType,
            String entityId,
            String detailsJson) {
        public AuditEvent {
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(entityId, "entityId");
        }
    }
}

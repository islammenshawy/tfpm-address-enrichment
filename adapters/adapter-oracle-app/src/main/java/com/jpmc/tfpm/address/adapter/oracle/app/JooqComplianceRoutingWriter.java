package com.jpmc.tfpm.address.adapter.oracle.app;

import com.jpmc.tfpm.address.domain.ComplianceDecision;
import com.jpmc.tfpm.address.domain.ComplianceRoutingWriter;
import com.jpmc.tfpm.address.domain.ComplianceRouter.ComplianceReason;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

import java.time.LocalDateTime;

import static org.jooq.impl.DSL.*;

@ThreadSafe
public class JooqComplianceRoutingWriter implements ComplianceRoutingWriter {

    private static final Logger LOG = LoggerFactory.getLogger(JooqComplianceRoutingWriter.class);

    private final DSLContext dsl;

    public JooqComplianceRoutingWriter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Retryable(retryFor = {TransientDataAccessException.class, org.jooq.exception.DataAccessException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public void record(long resultId, String countryHint, ComplianceDecision decision, String correlationId) {
        try {
            String decisionType;
            String primaryReason = null;
            String allReasonsJson = null;
            String urgency = null;

            switch (decision) {
                case ComplianceDecision.Bypass b -> decisionType = "BYPASS";
                case ComplianceDecision.RouteToCompliance r -> {
                    decisionType = "ROUTE";
                    primaryReason = r.primaryReason().name();
                    allReasonsJson = "[" + String.join(",",
                            r.allReasons().stream().map(cr -> "\"" + cr.name() + "\"").toList()) + "]";
                    urgency = r.urgency();
                }
                case ComplianceDecision.Block b -> {
                    decisionType = "BLOCK";
                    primaryReason = b.reason().name();
                    allReasonsJson = "[\"" + b.reason().name() + "\"]";
                    urgency = "BLOCKING";
                }
            }

            dsl.insertInto(table("COMPLIANCE_ROUTING"))
                    .columns(
                            field("RESULT_ID"),
                            field("COUNTRY_HINT"),
                            field("DECISION"),
                            field("PRIMARY_REASON"),
                            field("ALL_REASONS_JSON"),
                            field("URGENCY"),
                            field("STATUS"),
                            field("CREATED_AT"))
                    .values(
                            resultId,
                            countryHint,
                            decisionType,
                            primaryReason,
                            allReasonsJson,
                            urgency,
                            "PENDING",
                            LocalDateTime.now())
                    .execute();

            LOG.debug("Compliance routing recorded: decision={} resultId={} [corrId={}]",
                    decisionType, resultId, correlationId);
        } catch (Exception e) {
            LOG.error("Failed to persist compliance routing [corrId={}]", correlationId, e);
        }
    }
}

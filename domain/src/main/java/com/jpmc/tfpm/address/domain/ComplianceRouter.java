package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;

import java.util.Objects;
import java.util.Set;

/**
 * Decides whether an enriched address should bypass straight to the live
 * payment path or route to the existing JPMC compliance flow for review
 * before the payment proceeds.
 *
 * <p>The router does NOT do compliance work itself — it makes routing
 * decisions. The actual sanctions screening, AML evaluation, and KYC
 * checks remain the responsibility of the existing compliance platform.
 *
 * <p>See {@code docs/COMPLIANCE_INTEGRATION.md} for the four-axis decision
 * model (per-field confidence, overall confidence, country risk tiers,
 * pattern triggers), configuration, persistence, and the fail-safe
 * story when the compliance system is unreachable.
 *
 * <p>Implementations MUST be {@link ThreadSafe}.
 */
public interface ComplianceRouter {

    /**
     * Decide what to do with an enriched address.
     *
     * @param result   never null; the post-cascade structured output
     * @param request  never null; the original request, including raw
     *                 address text (needed for pattern matching)
     * @return one of {@link ComplianceDecision.Bypass},
     *         {@link ComplianceDecision.RouteToCompliance}, or
     *         {@link ComplianceDecision.Block}
     */
    ComplianceDecision evaluate(EnrichmentResult result, EnrichmentRequest request);

    /**
     * No-op router that always bypasses. Useful for tests and for the
     * initial Phase 1 dev environment before the real configuration
     * is loaded.
     */
    static ComplianceRouter alwaysBypass() {
        return (result, request) -> new ComplianceDecision.Bypass();
    }

    /**
     * The closed taxonomy of reasons a payment might route to compliance.
     * Adding a new reason is a deliberate decision affecting the
     * compliance contract; do not extend casually.
     */
    enum ComplianceReason {
        /** One or more required fields below the per-field confidence floor. */
        LOW_FIELD_CONFIDENCE,
        /** Aggregate confidence below the overall threshold. */
        LOW_OVERALL_CONFIDENCE,
        /** Country in the configured high-risk-countries list. */
        HIGH_RISK_COUNTRY,
        /** Raw address text matched a configured sanctions watch pattern. */
        SANCTIONS_PATTERN_MATCH,
        /** Structured fields exact-matched a known sanctioned entity. */
        SANCTIONS_EXACT_MATCH,
        /** Operator manually flagged this result for compliance review. */
        MANUAL_OVERRIDE,
        /** Cascade completed but SR2026-mandatory fields are missing. */
        SCHEMA_INCOMPLETE,
        /** Cascade returned no usable fields; cannot proceed without review. */
        UNSTRUCTURABLE
    }
}

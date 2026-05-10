package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.ComplianceRouter.ComplianceReason;

import java.util.Objects;
import java.util.Set;

/**
 * Outcome of a {@link ComplianceRouter#evaluate} call. Sealed so all
 * three outcomes are handled exhaustively at every call site via
 * pattern matching:
 *
 * <pre>{@code
 * return switch (router.evaluate(result, request)) {
 *     case ComplianceDecision.Bypass b ->
 *         publishToOutput(result);
 *     case ComplianceDecision.RouteToCompliance r ->
 *         dispatcher.dispatch(result, request, r);
 *     case ComplianceDecision.Block b ->
 *         persistBlockAndReturnError(result, b);
 * };
 * }</pre>
 *
 * <p>See {@code docs/COMPLIANCE_INTEGRATION.md} for the full decision
 * model and what each outcome means for downstream payment flow.
 */
public sealed interface ComplianceDecision
        permits ComplianceDecision.Bypass,
                ComplianceDecision.RouteToCompliance,
                ComplianceDecision.Block {

    /**
     * No compliance review needed; payment proceeds normally. The
     * cascade output is high-confidence and the address is not in any
     * triggered category.
     */
    record Bypass() implements ComplianceDecision {}

    /**
     * Send to compliance for review before the payment proceeds. The
     * compliance flow's verdict gates whether the payment continues.
     *
     * @param primaryReason the most important reason for routing;
     *                      drives the compliance system's prioritisation
     * @param allReasons    every reason that fired; useful for analytics
     *                      and for tuning the routing thresholds
     * @param urgency       processing urgency for the compliance side:
     *                      {@code "STANDARD"}, {@code "EXPEDITED"}, or
     *                      {@code "BLOCKING"} (cannot proceed without
     *                      verdict)
     */
    record RouteToCompliance(
            ComplianceReason primaryReason,
            Set<ComplianceReason> allReasons,
            String urgency) implements ComplianceDecision {

        public RouteToCompliance {
            Objects.requireNonNull(primaryReason, "primaryReason");
            Objects.requireNonNull(allReasons, "allReasons");
            Objects.requireNonNull(urgency, "urgency");
            if (!Set.of("STANDARD", "EXPEDITED", "BLOCKING").contains(urgency)) {
                throw new IllegalArgumentException(
                        "urgency must be STANDARD, EXPEDITED, or BLOCKING; got " + urgency);
            }
            allReasons = Set.copyOf(allReasons);
        }
    }

    /**
     * Hard block: the payment must not proceed regardless of compliance
     * verdict. Used only for OFAC SDN exact matches at very high
     * confidence — situations where there is no defensible reason to
     * even ask compliance.
     *
     * @param reason         the trigger; almost always {@link ComplianceReason#SANCTIONS_EXACT_MATCH}
     * @param justification  human-readable detail captured for audit
     */
    record Block(ComplianceReason reason, String justification)
            implements ComplianceDecision {

        public Block {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(justification, "justification");
        }
    }
}

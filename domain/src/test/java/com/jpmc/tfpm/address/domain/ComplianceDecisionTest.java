package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.ComplianceRouter.ComplianceReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ComplianceDecision")
class ComplianceDecisionTest {

    @Test
    void bypass_construction() {
        var decision = new ComplianceDecision.Bypass();
        assertThat(decision).isInstanceOf(ComplianceDecision.class);
    }

    @Test
    void route_to_compliance_valid_construction() {
        var reasons = Set.of(ComplianceReason.LOW_FIELD_CONFIDENCE, ComplianceReason.HIGH_RISK_COUNTRY);
        var decision = new ComplianceDecision.RouteToCompliance(
                ComplianceReason.LOW_FIELD_CONFIDENCE, reasons, "STANDARD");

        assertThat(decision.primaryReason()).isEqualTo(ComplianceReason.LOW_FIELD_CONFIDENCE);
        assertThat(decision.allReasons()).containsExactlyInAnyOrder(
                ComplianceReason.LOW_FIELD_CONFIDENCE, ComplianceReason.HIGH_RISK_COUNTRY);
        assertThat(decision.urgency()).isEqualTo("STANDARD");
    }

    @Test
    void route_to_compliance_accepts_expedited() {
        var decision = new ComplianceDecision.RouteToCompliance(
                ComplianceReason.SANCTIONS_PATTERN_MATCH,
                Set.of(ComplianceReason.SANCTIONS_PATTERN_MATCH),
                "EXPEDITED");
        assertThat(decision.urgency()).isEqualTo("EXPEDITED");
    }

    @Test
    void route_to_compliance_accepts_blocking() {
        var decision = new ComplianceDecision.RouteToCompliance(
                ComplianceReason.SANCTIONS_EXACT_MATCH,
                Set.of(ComplianceReason.SANCTIONS_EXACT_MATCH),
                "BLOCKING");
        assertThat(decision.urgency()).isEqualTo("BLOCKING");
    }

    @Test
    void route_to_compliance_rejects_invalid_urgency() {
        assertThatThrownBy(() -> new ComplianceDecision.RouteToCompliance(
                ComplianceReason.LOW_FIELD_CONFIDENCE,
                Set.of(ComplianceReason.LOW_FIELD_CONFIDENCE),
                "INVALID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("urgency");
    }

    @Test
    void route_to_compliance_rejects_null_primary_reason() {
        assertThatThrownBy(() -> new ComplianceDecision.RouteToCompliance(
                null, Set.of(ComplianceReason.LOW_FIELD_CONFIDENCE), "STANDARD"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void route_to_compliance_rejects_null_all_reasons() {
        assertThatThrownBy(() -> new ComplianceDecision.RouteToCompliance(
                ComplianceReason.LOW_FIELD_CONFIDENCE, null, "STANDARD"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void route_to_compliance_rejects_null_urgency() {
        assertThatThrownBy(() -> new ComplianceDecision.RouteToCompliance(
                ComplianceReason.LOW_FIELD_CONFIDENCE,
                Set.of(ComplianceReason.LOW_FIELD_CONFIDENCE), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void route_to_compliance_all_reasons_is_defensively_copied() {
        var reasons = new HashSet<>(Set.of(ComplianceReason.LOW_FIELD_CONFIDENCE));
        var decision = new ComplianceDecision.RouteToCompliance(
                ComplianceReason.LOW_FIELD_CONFIDENCE, reasons, "STANDARD");
        reasons.add(ComplianceReason.HIGH_RISK_COUNTRY);
        assertThat(decision.allReasons()).doesNotContain(ComplianceReason.HIGH_RISK_COUNTRY);
    }

    @Test
    void route_to_compliance_all_reasons_is_immutable() {
        var decision = new ComplianceDecision.RouteToCompliance(
                ComplianceReason.LOW_FIELD_CONFIDENCE,
                Set.of(ComplianceReason.LOW_FIELD_CONFIDENCE),
                "STANDARD");
        assertThatThrownBy(() -> decision.allReasons().add(ComplianceReason.HIGH_RISK_COUNTRY))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void block_construction() {
        var decision = new ComplianceDecision.Block(
                ComplianceReason.SANCTIONS_EXACT_MATCH, "OFAC SDN match on entity X");
        assertThat(decision.reason()).isEqualTo(ComplianceReason.SANCTIONS_EXACT_MATCH);
        assertThat(decision.justification()).isEqualTo("OFAC SDN match on entity X");
    }

    @Test
    void block_rejects_null_reason() {
        assertThatThrownBy(() -> new ComplianceDecision.Block(null, "justification"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void block_rejects_null_justification() {
        assertThatThrownBy(() -> new ComplianceDecision.Block(ComplianceReason.SANCTIONS_EXACT_MATCH, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void pattern_matching_exhaustive() {
        ComplianceDecision[] decisions = {
                new ComplianceDecision.Bypass(),
                new ComplianceDecision.RouteToCompliance(
                        ComplianceReason.LOW_FIELD_CONFIDENCE,
                        Set.of(ComplianceReason.LOW_FIELD_CONFIDENCE),
                        "STANDARD"),
                new ComplianceDecision.Block(ComplianceReason.SANCTIONS_EXACT_MATCH, "match")
        };

        for (var d : decisions) {
            String result = switch (d) {
                case ComplianceDecision.Bypass b -> "bypass";
                case ComplianceDecision.RouteToCompliance r -> "route";
                case ComplianceDecision.Block b -> "block";
            };
            assertThat(result).isNotEmpty();
        }
    }
}

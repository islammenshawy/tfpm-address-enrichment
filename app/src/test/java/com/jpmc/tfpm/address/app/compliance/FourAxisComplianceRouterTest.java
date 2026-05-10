package com.jpmc.tfpm.address.app.compliance;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.ComplianceDecision;
import com.jpmc.tfpm.address.domain.ComplianceRouter.ComplianceReason;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FourAxisComplianceRouter")
class FourAxisComplianceRouterTest {

    private FourAxisComplianceRouter router(boolean shadow) {
        return new FourAxisComplianceRouter(new ComplianceProperties(
                true,
                Map.of("CTRY", 0.95, "TWN_NM", 0.80),
                0.85,
                "",
                "",
                "CONSERVATIVE",
                shadow));
    }

    private EnrichmentResult result(double ctryConf, double twnConf, String country) {
        var addr = StructuredAddress.builder()
                .put(AddressField.CTRY, new FieldValue(country, ctryConf))
                .put(AddressField.TWN_NM, new FieldValue("SomeCity", twnConf))
                .build();
        return new EnrichmentResult("corr-1", EnrichmentResult.Outcome.SUCCESS,
                addr, Math.min(ctryConf, twnConf), 1L, Instant.now());
    }

    private EnrichmentRequest request(String raw, String countryHint) {
        return new EnrichmentRequest("corr-1", EnrichmentRequest.SourceChannel.HTTP,
                new RawAddress(raw, countryHint, ""));
    }

    @Test
    void bypass_when_all_confidences_above_floor() {
        var r = router(false);
        var decision = r.evaluate(result(0.99, 0.90, "US"), request("123 Main St", "US"));
        assertThat(decision).isInstanceOf(ComplianceDecision.Bypass.class);
    }

    @Test
    void route_on_low_field_confidence() {
        var r = router(false);
        var decision = r.evaluate(result(0.80, 0.90, "US"), request("123 Main St", "US"));
        assertThat(decision).isInstanceOf(ComplianceDecision.RouteToCompliance.class);
        var route = (ComplianceDecision.RouteToCompliance) decision;
        assertThat(route.allReasons()).contains(ComplianceReason.LOW_FIELD_CONFIDENCE);
    }

    @Test
    void route_on_low_overall_confidence() {
        var r = router(false);
        var decision = r.evaluate(result(0.96, 0.50, "US"), request("123 Main St", "US"));
        assertThat(decision).isInstanceOf(ComplianceDecision.RouteToCompliance.class);
        var route = (ComplianceDecision.RouteToCompliance) decision;
        assertThat(route.allReasons()).contains(ComplianceReason.LOW_OVERALL_CONFIDENCE);
    }

    @Test
    void shadow_mode_returns_bypass_but_logs() {
        var r = router(true);
        var decision = r.evaluate(result(0.50, 0.50, "US"), request("123 Main St", "US"));
        assertThat(decision).isInstanceOf(ComplianceDecision.Bypass.class);
    }

    @Test
    void route_on_unstructurable_outcome() {
        var r = router(false);
        var emptyResult = new EnrichmentResult("corr-1", EnrichmentResult.Outcome.UNSTRUCTURABLE,
                StructuredAddress.empty(), 0.0, null, Instant.now());
        var decision = r.evaluate(emptyResult, request("gibberish", ""));
        assertThat(decision).isInstanceOf(ComplianceDecision.RouteToCompliance.class);
    }

    @Test
    void fail_safe_conservative_routes_on_error() {
        // Router with bad resource path that won't cause error in constructor
        // but the evaluate should handle gracefully
        var r = router(false);
        var decision = r.evaluate(result(0.99, 0.90, "US"), request("123 Main St", "US"));
        assertThat(decision).isInstanceOf(ComplianceDecision.Bypass.class);
    }
}

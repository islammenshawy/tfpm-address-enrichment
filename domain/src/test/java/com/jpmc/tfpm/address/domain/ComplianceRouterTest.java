package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ComplianceRouter")
class ComplianceRouterTest {

    @Test
    void alwaysBypass_returns_bypass_for_any_input() {
        var router = ComplianceRouter.alwaysBypass();

        var result = new EnrichmentResult(
                "corr-1",
                EnrichmentResult.Outcome.SUCCESS,
                StructuredAddress.empty(),
                0.95,
                1L,
                Instant.now());

        var request = new EnrichmentRequest(
                "corr-1",
                EnrichmentRequest.SourceChannel.HTTP,
                RawAddress.of("123 Main St"));

        var decision = router.evaluate(result, request);
        assertThat(decision).isInstanceOf(ComplianceDecision.Bypass.class);
    }
}

package com.jpmc.tfpm.address.adapter.oracle.app;

import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.RawAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OracleIdempotencyStore")
class OracleIdempotencyStoreTest {

    @Test
    void computeKey_produces_sha256_hex() {
        var request = new EnrichmentRequest(
                "corr-1", EnrichmentRequest.SourceChannel.HTTP,
                RawAddress.of("123 Main St"));
        var key = OracleIdempotencyStore.computeKey(request);

        assertThat(key).hasSize(64); // SHA-256 = 32 bytes = 64 hex chars
        assertThat(key).matches("[0-9a-f]{64}");
    }

    @Test
    void computeKey_is_deterministic() {
        var request = new EnrichmentRequest(
                "corr-1", EnrichmentRequest.SourceChannel.HTTP,
                RawAddress.of("123 Main St"));

        assertThat(OracleIdempotencyStore.computeKey(request))
                .isEqualTo(OracleIdempotencyStore.computeKey(request));
    }

    @Test
    void computeKey_same_address_different_channels_produce_same_key() {
        var addr = RawAddress.of("123 Main St");
        var http = new EnrichmentRequest("corr", EnrichmentRequest.SourceChannel.HTTP, addr);
        var kafka = new EnrichmentRequest("corr", EnrichmentRequest.SourceChannel.KAFKA, addr);

        // P0-2: key is channel-agnostic — same address = same key regardless of channel
        assertThat(OracleIdempotencyStore.computeKey(http))
                .isEqualTo(OracleIdempotencyStore.computeKey(kafka));
    }

    @Test
    void computeKey_uses_canonical_address() {
        var addr1 = RawAddress.of("  123   Main   St  ");
        var addr2 = RawAddress.of("123 Main St");
        var req1 = new EnrichmentRequest("corr", EnrichmentRequest.SourceChannel.HTTP, addr1);
        var req2 = new EnrichmentRequest("corr", EnrichmentRequest.SourceChannel.HTTP, addr2);

        // canonical() normalizes whitespace and case
        assertThat(OracleIdempotencyStore.computeKey(req1))
                .isEqualTo(OracleIdempotencyStore.computeKey(req2));
    }

    @Test
    void computeKey_case_insensitive() {
        var req1 = new EnrichmentRequest("corr", EnrichmentRequest.SourceChannel.HTTP,
                RawAddress.of("Main Street"));
        var req2 = new EnrichmentRequest("corr", EnrichmentRequest.SourceChannel.HTTP,
                RawAddress.of("main street"));

        assertThat(OracleIdempotencyStore.computeKey(req1))
                .isEqualTo(OracleIdempotencyStore.computeKey(req2));
    }
}

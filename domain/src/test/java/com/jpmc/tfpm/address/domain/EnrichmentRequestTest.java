package com.jpmc.tfpm.address.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EnrichmentRequest")
class EnrichmentRequestTest {

    private static final RawAddress SAMPLE_ADDRESS = RawAddress.of("123 Main St");

    @ParameterizedTest
    @EnumSource(EnrichmentRequest.SourceChannel.class)
    void valid_construction_with_each_channel(EnrichmentRequest.SourceChannel channel) {
        var req = new EnrichmentRequest("corr-1", channel, SAMPLE_ADDRESS);
        assertThat(req.correlationId()).isEqualTo("corr-1");
        assertThat(req.sourceChannel()).isEqualTo(channel);
        assertThat(req.address()).isEqualTo(SAMPLE_ADDRESS);
    }

    @Test
    void rejects_blank_correlation_id() {
        assertThatThrownBy(() -> new EnrichmentRequest("  ", EnrichmentRequest.SourceChannel.HTTP, SAMPLE_ADDRESS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejects_empty_correlation_id() {
        assertThatThrownBy(() -> new EnrichmentRequest("", EnrichmentRequest.SourceChannel.HTTP, SAMPLE_ADDRESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null_correlation_id() {
        assertThatThrownBy(() -> new EnrichmentRequest(null, EnrichmentRequest.SourceChannel.HTTP, SAMPLE_ADDRESS))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_null_source_channel() {
        assertThatThrownBy(() -> new EnrichmentRequest("corr-1", null, SAMPLE_ADDRESS))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_null_address() {
        assertThatThrownBy(() -> new EnrichmentRequest("corr-1", EnrichmentRequest.SourceChannel.HTTP, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void all_source_channels_exist() {
        assertThat(EnrichmentRequest.SourceChannel.values())
                .containsExactly(
                        EnrichmentRequest.SourceChannel.HTTP,
                        EnrichmentRequest.SourceChannel.KAFKA,
                        EnrichmentRequest.SourceChannel.RABBITMQ);
    }
}

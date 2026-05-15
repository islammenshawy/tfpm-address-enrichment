package com.jpmc.tfpm.address.inbound.kafka;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AddressEnrichmentKafkaListener")
class AddressEnrichmentKafkaListenerTest {

    private AddressEnrichmentService service;
    private Acknowledgment ack;
    private AddressEnrichmentKafkaListener listener;

    @BeforeEach
    void setUp() {
        service = mock(AddressEnrichmentService.class);
        ack = mock(Acknowledgment.class);
        listener = new AddressEnrichmentKafkaListener(service, new ObjectMapper());
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("topic", 0, 0L, "key-1", value);
    }

    private EnrichmentResult successResult() {
        return new EnrichmentResult("corr", EnrichmentResult.Outcome.SUCCESS,
                StructuredAddress.empty(), 0.9, 1L, Instant.now());
    }

    @Test
    void processes_valid_message_and_acks() {
        when(service.enrich(any(EnrichmentRequest.class))).thenReturn(successResult());

        listener.onMessage(record("""
                {"rawAddress": "123 Main St", "countryHint": "US"}
                """), ack);

        verify(service).enrich(any(EnrichmentRequest.class));
        verify(ack).acknowledge();
    }

    @Test
    void acks_on_empty_raw_address() {
        listener.onMessage(record("""
                {"rawAddress": "", "countryHint": "US"}
                """), ack);

        verify(service, never()).enrich(any());
        verify(ack).acknowledge();
    }

    @Test
    void rethrows_service_exception_for_dlt() {
        when(service.enrich(any(EnrichmentRequest.class)))
                .thenThrow(new RuntimeException("db down"));

        // Transient errors are rethrown so Spring Kafka's error handler can retry/DLT
        assertThatThrownBy(() -> listener.onMessage(record("""
                {"rawAddress": "test address"}
                """), ack))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db down");

        verify(ack, never()).acknowledge();
    }

    @Test
    void acks_malformed_json_to_prevent_redelivery() {
        // Bad input is permanently unprocessable — ack it so it doesn't loop
        listener.onMessage(record("not json at all"), ack);

        verify(service, never()).enrich(any());
        verify(ack).acknowledge();
    }
}

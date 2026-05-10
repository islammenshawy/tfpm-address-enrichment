package com.jpmc.tfpm.address.inbound.mq;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AddressEnrichmentMqListener")
class AddressEnrichmentMqListenerTest {

    private AddressEnrichmentService service;
    private AddressEnrichmentMqListener listener;

    @BeforeEach
    void setUp() {
        service = mock(AddressEnrichmentService.class);
        listener = new AddressEnrichmentMqListener(service);
    }

    private TextMessage textMessage(String body) throws JMSException {
        var msg = mock(TextMessage.class);
        when(msg.getText()).thenReturn(body);
        when(msg.getJMSMessageID()).thenReturn("msg-123");
        return msg;
    }

    private EnrichmentResult successResult() {
        return new EnrichmentResult("corr", EnrichmentResult.Outcome.SUCCESS,
                StructuredAddress.empty(), 0.9, 1L, Instant.now());
    }

    @Test
    void processes_valid_message_and_acks() throws Exception {
        when(service.enrich(any(EnrichmentRequest.class))).thenReturn(successResult());
        var msg = textMessage("123 Main St, New York");

        listener.onMessage(msg);

        verify(service).enrich(any(EnrichmentRequest.class));
        verify(msg).acknowledge();
    }

    @Test
    void acks_on_blank_message() throws Exception {
        var msg = textMessage("   ");

        listener.onMessage(msg);

        verify(service, never()).enrich(any());
        verify(msg).acknowledge();
    }

    @Test
    void does_not_ack_on_service_exception() throws Exception {
        when(service.enrich(any(EnrichmentRequest.class)))
                .thenThrow(new RuntimeException("db down"));
        var msg = textMessage("test address");

        listener.onMessage(msg);

        verify(msg, never()).acknowledge();
    }

    @Test
    void uses_correlation_id_from_jms_property() throws Exception {
        when(service.enrich(any(EnrichmentRequest.class))).thenReturn(successResult());
        var msg = textMessage("test");
        when(msg.getStringProperty("X-Correlation-Id")).thenReturn("custom-corr");

        listener.onMessage(msg);

        verify(service).enrich(argThat(req -> req.correlationId().equals("custom-corr")));
    }
}

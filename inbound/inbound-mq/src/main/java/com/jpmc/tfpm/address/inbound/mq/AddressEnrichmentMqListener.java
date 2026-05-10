package com.jpmc.tfpm.address.inbound.mq;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.ThreadSafe;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.RawAddress;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * IBM MQ JMS listener for address enrichment messages.
 * Client-ack mode: acknowledge after service returns (Oracle commit inside).
 */
@Component
@ThreadSafe
public class AddressEnrichmentMqListener {

    private static final Logger LOG = LoggerFactory.getLogger(AddressEnrichmentMqListener.class);

    private final AddressEnrichmentService service;

    public AddressEnrichmentMqListener(AddressEnrichmentService service) {
        this.service = service;
    }

    @JmsListener(
            destination = "${enrichment.mq.input-queue:TFPM.PAYMENT.IN}",
            containerFactory = "jmsListenerContainerFactory")
    public void onMessage(Message message) throws JMSException {
        var correlationId = extractCorrelationId(message);
        MDC.put("traceId", correlationId);
        try {
            LOG.debug("Received MQ message [corrId={}]", correlationId);

            if (!(message instanceof TextMessage textMessage)) {
                LOG.warn("Non-text JMS message received, skipping [corrId={}]", correlationId);
                message.acknowledge();
                return;
            }

            var body = textMessage.getText();
            if (body == null || body.isBlank()) {
                LOG.warn("Empty MQ message body [corrId={}]", correlationId);
                message.acknowledge();
                return;
            }

            var request = new EnrichmentRequest(
                    correlationId,
                    EnrichmentRequest.SourceChannel.MQ,
                    RawAddress.of(body.trim()));

            var result = service.enrich(request);
            LOG.info("MQ enrichment complete: outcome={} [corrId={}]",
                    result.outcome(), correlationId);

            message.acknowledge();
        } catch (Exception e) {
            LOG.error("Failed to process MQ message [corrId={}]", correlationId, e);
            // Don't ack — message will be redelivered by MQ
        } finally {
            MDC.remove("traceId");
        }
    }

    private String extractCorrelationId(Message message) throws JMSException {
        var corrId = message.getStringProperty("X-Correlation-Id");
        if (corrId != null && !corrId.isBlank()) return corrId;
        var jmsId = message.getJMSMessageID();
        return jmsId != null ? jmsId : UUID.randomUUID().toString();
    }
}

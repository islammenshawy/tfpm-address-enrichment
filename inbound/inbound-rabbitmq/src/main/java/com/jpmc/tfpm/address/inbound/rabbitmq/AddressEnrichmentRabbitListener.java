package com.jpmc.tfpm.address.inbound.rabbitmq;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * RabbitMQ listener for address enrichment messages.
 * Manual ack only after service returns (Oracle commit inside).
 * Configurable on/off via {@code enrichment.rabbitmq.enabled}.
 */
@Component
@ThreadSafe
public class AddressEnrichmentRabbitListener {

    private static final Logger LOG = LoggerFactory.getLogger(AddressEnrichmentRabbitListener.class);

    private final AddressEnrichmentService service;
    private final ObjectMapper objectMapper;

    public AddressEnrichmentRabbitListener(AddressEnrichmentService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(
            queues = "${enrichment.rabbitmq.input-queue:tfpm.address.enrichment.input}",
            ackMode = "MANUAL",
            autoStartup = "${enrichment.rabbitmq.enabled:false}")
    public void onMessage(Message message, Channel channel) throws IOException {
        var correlationId = extractCorrelationId(message);
        MDC.put("traceId", correlationId);
        try {
            LOG.debug("Received RabbitMQ message [corrId={}]", correlationId);

            var body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            if (body.isBlank()) {
                LOG.warn("Empty RabbitMQ message body [corrId={}]", correlationId);
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }

            var json = objectMapper.readTree(body);
            var rawAddress = json.path("rawAddress").asText("");
            var countryHint = json.path("countryHint").asText("");
            var locale = json.path("locale").asText("");

            if (rawAddress.isBlank()) {
                LOG.warn("Empty rawAddress in RabbitMQ message [corrId={}]", correlationId);
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }

            var request = new EnrichmentRequest(
                    correlationId,
                    EnrichmentRequest.SourceChannel.RABBITMQ,
                    new RawAddress(rawAddress, countryHint, locale));

            var result = service.enrich(request);
            LOG.info("RabbitMQ enrichment complete: outcome={} [corrId={}]",
                    result.outcome(), correlationId);

            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            LOG.error("Failed to process RabbitMQ message [corrId={}]", correlationId, e);
            // Nack with requeue=true for retry, requeue=false for DLQ
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
        } finally {
            MDC.remove("traceId");
        }
    }

    private String extractCorrelationId(Message message) {
        var props = message.getMessageProperties();
        if (props.getCorrelationId() != null && !props.getCorrelationId().isBlank()) {
            return props.getCorrelationId();
        }
        var header = props.getHeader("X-Correlation-Id");
        if (header != null) {
            return header.toString();
        }
        return UUID.randomUUID().toString();
    }
}

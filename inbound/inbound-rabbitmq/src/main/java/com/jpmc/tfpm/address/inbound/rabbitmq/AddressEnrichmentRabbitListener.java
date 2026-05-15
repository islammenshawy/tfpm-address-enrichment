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
    private static final int MAX_REDELIVERY_COUNT = 3;

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
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // Permanent failure — bad input, parse errors. Send to DLQ (requeue=false).
            LOG.warn("Permanently unprocessable RabbitMQ message, sending to DLQ [corrId={}]: {}",
                    correlationId, e.getMessage(), e);
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
        } catch (Exception e) {
            // Transient failure (DB errors, timeouts, etc.).
            // Check redelivery count — if exceeded max retries, send to DLQ to avoid infinite loop.
            long redeliveryCount = getRedeliveryCount(message);
            if (redeliveryCount >= MAX_REDELIVERY_COUNT) {
                LOG.error("Max redelivery count ({}) exceeded for RabbitMQ message, sending to DLQ [corrId={}]",
                        MAX_REDELIVERY_COUNT, correlationId, e);
                channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
            } else {
                LOG.warn("Transient failure processing RabbitMQ message (redelivery {}), requeueing for retry [corrId={}]",
                        redeliveryCount, correlationId, e);
                channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
            }
        } finally {
            MDC.remove("traceId");
        }
    }

    private long getRedeliveryCount(Message message) {
        var props = message.getMessageProperties();
        // x-death header is set by RabbitMQ when a message is dead-lettered and requeued.
        // Each cycle increments the count. If not present, fall back to isRedelivered().
        var xDeath = props.getXDeathHeader();
        if (xDeath != null && !xDeath.isEmpty()) {
            var firstEntry = xDeath.get(0);
            var count = firstEntry.get("count");
            if (count instanceof Number n) {
                return n.longValue();
            }
        }
        // Fallback: if the message has been redelivered at all, count as 1
        return Boolean.TRUE.equals(props.isRedelivered()) ? 1 : 0;
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

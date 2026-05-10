package com.jpmc.tfpm.address.inbound.kafka;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.RawAddress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Kafka listener for address enrichment messages.
 * Manual ack only after service returns (Oracle commit inside).
 */
@Component
public class AddressEnrichmentKafkaListener {

    private static final Logger LOG = LoggerFactory.getLogger(AddressEnrichmentKafkaListener.class);

    private final AddressEnrichmentService service;
    private final ObjectMapper objectMapper;

    public AddressEnrichmentKafkaListener(AddressEnrichmentService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${enrichment.kafka.input-topic:address-enrichment-input}",
            groupId = "${enrichment.kafka.consumer-group:tfpm-address-enrichment}",
            autoStartup = "${enrichment.kafka.enabled:false}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        var correlationId = extractCorrelationId(record);
        LOG.debug("Received Kafka message [corrId={}, partition={}, offset={}]",
                correlationId, record.partition(), record.offset());

        try {
            var json = objectMapper.readTree(record.value());
            var rawAddress = json.path("rawAddress").asText("");
            var countryHint = json.path("countryHint").asText("");
            var locale = json.path("locale").asText("");

            if (rawAddress.isBlank()) {
                LOG.warn("Empty rawAddress in Kafka message [corrId={}]", correlationId);
                ack.acknowledge();
                return;
            }

            var request = new EnrichmentRequest(
                    correlationId,
                    EnrichmentRequest.SourceChannel.KAFKA,
                    new RawAddress(rawAddress, countryHint, locale));

            var result = service.enrich(request);
            LOG.info("Kafka enrichment complete: outcome={} [corrId={}]",
                    result.outcome(), correlationId);

            ack.acknowledge();
        } catch (Exception e) {
            LOG.error("Failed to process Kafka message [corrId={}]", correlationId, e);
            // Don't ack — message will be redelivered by Kafka
        }
    }

    private String extractCorrelationId(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("X-Correlation-Id");
        if (header != null) {
            return new String(header.value());
        }
        return record.key() != null ? record.key() : UUID.randomUUID().toString();
    }
}

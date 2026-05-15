package com.jpmc.tfpm.address.domain;

import java.util.Objects;

/**
 * Channel-agnostic enrichment request. The same type is produced by all
 * three inbound channels (HTTP, Kafka, RabbitMQ) after normalising their
 * channel-specific message formats.
 *
 * <p>This is the only type the {@link AddressEnrichmentService} accepts on
 * its primary entry point, ensuring the channels stay symmetric and the
 * service remains channel-blind.
 *
 * @param correlationId unique identifier for this request, propagated to
 *                      all logs, metrics, OpenTelemetry traces, and Oracle
 *                      audit rows. The channel adapter is responsible for
 *                      providing one (HTTP: header or generated UUID;
 *                      Kafka: header; RabbitMQ: message id). Never null.
 * @param sourceChannel which inbound channel produced this request. Used
 *                      for metrics tagging and the idempotency key.
 * @param address       the raw address to structure. Never null.
 */
public record EnrichmentRequest(
        String correlationId,
        SourceChannel sourceChannel,
        RawAddress address) {
    public EnrichmentRequest {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(sourceChannel, "sourceChannel");
        Objects.requireNonNull(address, "address");
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
    }

    public enum SourceChannel {
        HTTP,
        KAFKA,
        RABBITMQ,
        BACKFILL
    }
}

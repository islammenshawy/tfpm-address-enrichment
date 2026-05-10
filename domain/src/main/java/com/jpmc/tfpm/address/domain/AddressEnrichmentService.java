package com.jpmc.tfpm.address.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The single entry point all three inbound channels (HTTP, Kafka, IBM MQ)
 * converge on. The implementation lives in the {@code app} module and
 * coordinates idempotency, the cascade, persistence, and exception queue
 * handling.
 *
 * <p>Implementations MUST be {@code @ThreadSafe}.
 *
 * <h2>Idempotency contract</h2>
 *
 * <p>Calling {@link #enrich(EnrichmentRequest)} multiple times with
 * requests that share the same idempotency key
 * ({@code SHA-256(address.canonical() || sourceChannel)}) MUST yield the
 * same {@link EnrichmentResult} and MUST result in exactly one row in
 * {@code TFPM_ADDR_ENRICH.STRUCTURING_RESULTS} regardless of which
 * replica handled which call.
 *
 * <p>The implementation enforces this via Oracle's unique constraint on
 * {@code IDEMPOTENCY_KEYS.IDEM_KEY}: INSERT-first-catch-ORA-00001.
 */
public interface AddressEnrichmentService {

    /**
     * Enrich a single raw address. Idempotent across replicas.
     *
     * @param request never null; correlation id and source channel must
     *                be populated by the inbound adapter
     * @return never null; outcome may be {@link EnrichmentResult.Outcome#SUCCESS},
     *         {@link EnrichmentResult.Outcome#REQUIRES_REVIEW}, or
     *         {@link EnrichmentResult.Outcome#PERSISTED_DUPLICATE} if the
     *         idempotency key was already processed
     */
    EnrichmentResult enrich(EnrichmentRequest request);

    /**
     * Result of an enrichment call.
     *
     * @param correlationId       echo of the request correlation id
     * @param outcome             classification of the result
     * @param structuredAddress   the post-cascade address; never null;
     *                            may be {@link StructuredAddress#empty()}
     *                            if no structurer could infer anything
     * @param overallConfidence   calibrated min-across-required-fields,
     *                            in [0.0, 1.0]
     * @param resultRowId         primary key in
     *                            {@code STRUCTURING_RESULTS}; null only
     *                            if the cascade returned nothing usable
     *                            and no row was written
     * @param processedAt         server-side timestamp
     */
    record EnrichmentResult(
            String correlationId,
            Outcome outcome,
            StructuredAddress structuredAddress,
            double overallConfidence,
            Long resultRowId,
            Instant processedAt) {

        public EnrichmentResult {
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(structuredAddress, "structuredAddress");
            Objects.requireNonNull(processedAt, "processedAt");
        }

        public boolean isSuccess() {
            return outcome == Outcome.SUCCESS
                    || outcome == Outcome.PERSISTED_DUPLICATE;
        }

        public enum Outcome {
            /** Cascade completed, result persisted, confidence above threshold. */
            SUCCESS,

            /** Cascade completed, result persisted, but confidence below threshold;
             *  written to EXCEPTION_QUEUE for human review. */
            REQUIRES_REVIEW,

            /** Idempotency key matched a prior successful processing; cached
             *  result returned. No new cascade run. */
            PERSISTED_DUPLICATE,

            /** Cascade returned no usable fields; nothing persisted; written
             *  to EXCEPTION_QUEUE with REASON='UNSTRUCTURABLE'. */
            UNSTRUCTURABLE
        }
    }
}

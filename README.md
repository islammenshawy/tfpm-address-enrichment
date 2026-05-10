# TFPM Address Enrichment Service

Convert unstructured postal addresses in trade-finance payment flows to ISO 20022
structured/hybrid format ahead of the SWIFT SR2026 deadline (**14 November 2026**).

This is a **shadow-mode** service: it runs alongside the existing payment path,
reads from the legacy Oracle store, structures addresses through a plugin-based
cascade (libpostal -> SWIFT CRF -> LLM gateway), and writes results to a separate
Oracle schema for review and eventual cutover.

## Architecture

```
                    +-----------------------------------------+
                    |  PROD PAYMENT PATH (untouched)          |
                    |  TPS -> Oracle -> Kafka -> MX -> SWIFT  |
                    +------------------+----------------------+
                                       |
                          tee / CDC / corporate file copy
                                       |
                                       v
    +--------------------------------------------------------------+
    |  Inbound channels (each replica handles all three)            |
    |   +--------+    +---------+    +----------+                   |
    |   | HTTP   |    | Kafka   |    | IBM MQ   |                   |
    |   +---+----+    +----+----+    +-----+----+                   |
    |       +==============+===============+                        |
    |                      v                                        |
    |          AddressEnrichmentService                             |
    |                      |                                        |
    |         +--- CascadeOrchestrator ---+                         |
    |         |            |              |                         |
    |         v            v              v                         |
    |     libpostal    swift-crf        llm        (future plugin)  |
    |     (gRPC)       (gRPC)         (HTTP)                        |
    |         |            |              |                         |
    |         +============+==============+                         |
    |                      |                                        |
    |          FieldMerger + Calibrators                            |
    |                      |                                        |
    |          StructuredAddress + provenance                       |
    |                      |                                        |
    |   +------------------+------------------+                     |
    |   v                                     v                     |
    |  adapter-prowide            adapter-oracle-app                |
    |  (PstlAdr build)            (TFPM_ADDR_ENRICH schema)        |
    +--------------------------------------------------------------+
                                       |
                                       v
                       Oracle: TFPM_ADDR_ENRICH schema
                       (idempotency + results + exceptions + audit)
```

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| **`Result<T>` instead of exceptions** | Fallible operations return `Result.Success` or `Result.Failure` with `EnrichmentError`. Callers handle both via pattern matching. No try/catch ceremony; retry layers inspect `error.isRetryable()`. |
| **INSERT-first-catch-ORA-00001** | Idempotency without race conditions. Never SELECT-then-INSERT (two replicas could both see "not found"). Oracle's unique constraint is the coordination point. |
| **Two Oracle pools, two users** | `TFPM_LEGACY_RO` (SELECT-only) and `TFPM_ADDR_ENRICH_APP` (DML-only). Even a bug can't write to legacy or DDL the new schema. |
| **No XA for IBM MQ** | Idempotency table + JMS client-ack provides exactly-once semantics without XA overhead. |
| **Cooperative-sticky Kafka assignor** | Minimizes rebalance disruption; partitions stick to the same consumer across rebalances. |
| **Five retry layers** | Per-RPC -> per-structurer -> persistence -> service -> channel-native. Each bounded; `EnrichmentError.isRetryable()` is the single decision point. |
| **`@ThreadSafe` + ArchUnit** | Compile-time enforcement: all fields final, only immutable/concurrent types. Catches drift before it reaches production. |
| **Per-field merging** | Each structurer contributes its strengths. libpostal wins STRT_NM; LLM wins CTRY. Cascade strictly improves as more structurers join. |
| **`@Calibrated` annotation** | Every structurer must have a `ConfidenceCalibrator`. Raw scores (log-likelihood, probability, LLM output) are normalized to [0,1] before the merger compares them. |
| **Plugin contract** | Adding a structurer = new module + bean + config. Zero changes to existing code. ArchUnit enforces this. |

## Module Structure

```
tfpm-address-enrichment/
+-- domain/                     Pure Java contracts, value objects, sealed types. Zero Spring deps.
+-- adapters/
|   +-- adapter-libpostal/      gRPC client to libpostal sidecar
|   +-- adapter-swift-crf/      Stub (disabled) for future SWIFT CRF model
|   +-- adapter-llm/            HTTP clients (JPMC gateway + OpenAI-compatible)
|   +-- adapter-prowide/        ISO 20022 PstlAdr mapping via Prowide
|   +-- adapter-oracle-legacy/  Read-only jOOQ on legacy address schema
|   +-- adapter-oracle-app/     Read-write jOOQ on TFPM_ADDR_ENRICH schema
+-- inbound/
|   +-- inbound-http/           REST controller (POST /api/v1/enrich)
|   +-- inbound-kafka/          Kafka listener with manual ack
|   +-- inbound-mq/             IBM MQ JMS listener with client ack
+-- app/                        Spring Boot composition root, cascade, service impl
+-- archunit-tests/             Architectural rules as build-time tests
+-- integration-tests/          Testcontainers end-to-end (Oracle, Kafka, MQ)
+-- infra/
|   +-- liquibase/              Schema migrations (8 tables, partitioned)
|   +-- docker/                 Local dev compose (Oracle, Kafka, MQ, WireMock)
+-- proto/                      gRPC contract (structurer.proto v1)
+-- docs/                       Architecture, country strategy, compliance, accuracy
```

## Quickstart

```bash
# Prerequisites: Java 21, Maven 3.9+, Docker

# 1. Start local infrastructure
make up && make wait && make migrate

# 2. Build and test everything
mvn clean verify

# 3. Run the app
mvn -pl app spring-boot:run

# 4. Test enrichment
curl -X POST http://localhost:8080/api/v1/enrich \
  -H 'Content-Type: application/json' \
  -d '{"rawAddress": "10 Downing Street, London SW1A 1AA, UK", "countryHint": "GB"}'
```

## Testing Strategy

| Layer | Framework | Scope |
|-------|-----------|-------|
| **Domain unit** | JUnit 5 + AssertJ + jqwik | Records, sealed types, validation, Result<T> functor laws |
| **App unit** | JUnit 5 + Mockito | CascadeOrchestrator, FieldMerger, ServiceImpl with mocked interfaces |
| **Adapter unit** | JUnit 5 + Mockito | Idempotency key computation, Prowide round-trip, channel message parsing |
| **ArchUnit** | ArchUnit 1.3 | Module boundaries, thread-safety, shadow-mode invariant, plugin contract |
| **Integration** | Testcontainers + Awaitility | Real Oracle + Kafka + MQ; multi-container idempotency, golden-set accuracy |

Run tests: `mvn test` (unit), `mvn verify -Pit` (integration)

## Configuration

Key properties in `app/src/main/resources/application.yml`:

| Property | Default | Description |
|----------|---------|-------------|
| `enrichment.cascade.order` | `[libpostal, swift-crf, llm]` | Structurer invocation order |
| `enrichment.cascade.early-exit-threshold` | `0.92` | Stop cascade if required fields above this |
| `enrichment.cascade.review-threshold` | `0.70` | Below this -> exception queue |
| `enrichment.libpostal.enabled` | `true` | Enable libpostal structurer |
| `enrichment.swift-crf.enabled` | `false` | Enable SWIFT CRF (stub) |
| `enrichment.llm.enabled` | `true` | Enable LLM structurer |
| `enrichment.llm.client` | `jpmc-internal-gateway` | Which LLM client to use |

## Operational Notes

- **Health**: `/actuator/health` — checks Oracle pools, Kafka, MQ connectivity
- **Metrics**: `/actuator/prometheus` — counters for processed, confidence, latency, cascade fallback, exceptions
- **Graceful shutdown**: `server.shutdown=graceful` with 30s timeout
- **Replicas**: Designed for 3-4 replicas; idempotency table provides cross-replica exactly-once
- **Shadow mode**: Never writes to `payments.*` topics or legacy Oracle schema (ArchUnit enforced)

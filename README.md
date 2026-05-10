# TFPM Address Enrichment Service

## What This Project Does

Financial institutions transmit payment messages over the SWIFT network using a standardized format called ISO 20022. One critical component of every cross-border payment is the **postal address** of parties involved (originator, beneficiary, intermediary banks). Today, most trade-finance payment systems store these addresses as **unstructured free text** — a single blob like `"Office 12, Tower 3, Sheikh Zayed Road, Dubai, UAE"`.

Starting **14 November 2026** (SWIFT Standards Release 2026), the SWIFT network will require addresses in **structured format** — individual fields for street name, building number, city, postal code, country, and subdivision. Payments with unstructured addresses will be rejected.

This service solves that problem. It takes unstructured address text and breaks it down into the correct ISO 20022 structured fields:

```
INPUT:  "Office 12, Tower 3, Sheikh Zayed Road, Dubai, UAE"

OUTPUT:
  CTRY          = AE           (confidence: 0.99)
  TWN_NM        = Dubai        (confidence: 0.95)
  CTRY_SUB_DVSN = Dubai        (confidence: 0.92)
  STRT_NM       = Sheikh Zayed Road  (confidence: 0.88)
  BLDG_NM       = Tower 3      (confidence: 0.85)
  BLDG_NB       = 12           (confidence: 0.90)
```

Each field carries a **calibrated confidence score** so downstream systems know how much to trust each extraction. Low-confidence results are routed to a human review queue.

## Why It's Hard

Address parsing is deceptively difficult:

- **No universal format.** UAE uses PO Boxes and tower names. China reverses the order (province first). Singapore uses block-street-unit notation. UK postcodes look nothing like US ZIP codes.
- **Abbreviations and transliteration.** "SZR" = "Sheikh Zayed Road". "Jing An" might be Pinyin for a Chinese district. "Str." might be "Strasse" or "Street".
- **Multiple correct answers.** In Dubai, the emirate "Dubai" is simultaneously the country subdivision, the city, and sometimes the district.
- **Legacy data quality.** Addresses stored 20 years ago in Oracle may have truncated fields, concatenated lines, or mixed languages.

No single parsing tool handles all of this well. libpostal excels at US/UK addresses but struggles with Chinese. LLMs handle ambiguity well but are slower and less deterministic. SWIFT's own CRF model is tuned for payment-specific patterns.

## How It Works

### The Cascade: Multiple Structurers, Best-of-Each-Field

Instead of relying on one parser, the service runs a **cascade of structurers** — each one attempts to parse the address, and a **per-field merger** picks the best result:

```
Raw Address
    |
    v
+-- CascadeOrchestrator -----------------------------------+
|                                                           |
|  1. libpostal (gRPC sidecar)                             |
|     Fast, statistical parser. Good for Western addresses. |
|     Returns: STRT_NM=0.92, TWN_NM=0.85, CTRY=0.60       |
|                                                           |
|  2. SWIFT CRF (gRPC sidecar) [future]                   |
|     Payment-domain-specific model from SWIFT.             |
|     Returns: TWN_NM=0.91, CTRY=0.95                      |
|                                                           |
|  3. LLM Gateway (HTTP)                                   |
|     GPT-4o / internal model with country-specific prompts.|
|     Returns: CTRY=0.99, TWN_NM=0.93, BLDG_NM=0.87       |
|                                                           |
+-----------------------------------------------------------+
    |
    v
FieldMerger (per-field vote: highest calibrated confidence wins)
    |
    v
Final: STRT_NM from libpostal (0.92)
       TWN_NM from LLM (0.93)
       CTRY from LLM (0.99)
       BLDG_NM from LLM (0.87)
```

This **per-field merging** means each structurer contributes its strengths. libpostal might win on street names (it was trained on OpenStreetMap), while the LLM wins on country identification (it understands "UAE" = "AE"). The cascade strictly improves as more structurers are added.

### Confidence Calibration

Different structurers report confidence on incompatible scales:
- libpostal returns log-likelihoods (e.g., -0.3)
- CRF models return marginal probabilities (e.g., 0.85)
- LLMs return whatever the prompt asks for

The **ConfidenceCalibrator** per structurer normalizes these to a common [0.0, 1.0] calibrated probability of correctness, learned against a golden set of human-verified addresses. Without calibration, comparing raw scores across structurers would produce nonsensical merges.

### Three Input Channels, One Service

The service accepts addresses from three sources simultaneously:

| Channel | Protocol | Use Case | Volume |
|---------|----------|----------|--------|
| **HTTP REST** | `POST /api/v1/enrich` | Ad-hoc lookups, UI-driven enrichment | < 10 req/sec |
| **Kafka** | Consumer with manual ack | Real-time payment event stream | 100-500 msg/sec |
| **IBM MQ** | JMS listener with client ack | Legacy SWIFT MT message flow | 10-50 msg/sec |

All three channels normalize their input to the same `EnrichmentRequest` and call the same `AddressEnrichmentService.enrich()`. The service is channel-blind — it doesn't know or care whether the address came from HTTP, Kafka, or MQ.

### Exactly-Once Processing Across Replicas

The service runs as **3-4 replicas** for high availability. The same address might arrive at different replicas via different channels simultaneously. The **idempotency table** in Oracle ensures exactly-once processing:

```
Replica 1 (HTTP)  ──┐
Replica 2 (Kafka) ──┼──> INSERT INTO IDEMPOTENCY_KEYS (key=SHA-256(address|channel))
Replica 3 (MQ)    ──┘
                         |
                    ORA-00001 (unique constraint violation)
                         |
                    First one wins → runs cascade → persists result
                    Others → load cached result → return immediately
```

This INSERT-first-catch-duplicate pattern is race-free because Oracle's unique constraint is the single coordination point. No distributed locks, no Redis, no SELECT-before-INSERT race windows.

### Shadow Mode: Safe Parallel Operation

This service runs in **shadow mode** — it operates alongside the production payment path but **never modifies production data**:

- Never writes to `payments.*` Kafka topics
- Never writes to the legacy Oracle schema
- All outputs go to a separate `TFPM_ADDR_ENRICH` schema
- ArchUnit tests enforce these invariants at compile time

This means the service can be deployed, tuned, and validated against real payment traffic without any risk to production payments. Cutover to production is a separate, future program.

### What Happens to Low-Confidence Results

Not every address can be parsed with high confidence. The service classifies each result:

| Outcome | Criteria | Action |
|---------|----------|--------|
| **SUCCESS** | All mandatory fields present, confidence >= 0.70 | Result persisted, ready for use |
| **REQUIRES_REVIEW** | Missing mandatory fields OR confidence < 0.70 | Routed to exception queue for human review |
| **UNSTRUCTURABLE** | No structurer produced any usable fields | Logged for investigation, queued for manual entry |
| **PERSISTED_DUPLICATE** | Idempotency key already processed | Cached result returned immediately |

The **exception queue** uses Oracle's `SELECT ... FOR UPDATE SKIP LOCKED` pattern so multiple human reviewers (makers) can claim work concurrently without blocking each other.

### Compliance Routing

After enrichment, a four-axis compliance router evaluates whether the structured address needs compliance review:

1. **Per-field confidence floors** — Is CTRY confidence >= 0.95? Is TWN_NM >= 0.80?
2. **Overall confidence floor** — Is the aggregate >= 0.85?
3. **Country risk tiers** — Is the country on OFAC/sanctions lists?
4. **Pattern triggers** — Does the raw text match sanctioned entity patterns?

Results are classified as **Bypass** (proceed normally), **Route to Compliance** (needs review before payment proceeds), or **Block** (hard stop, e.g., OFAC SDN exact match).

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
    |  Address Enrichment Service (3-4 replicas)                    |
    |                                                               |
    |   +--------+    +---------+    +----------+                   |
    |   | HTTP   |    | Kafka   |    | IBM MQ   |                   |
    |   +---+----+    +----+----+    +-----+----+                   |
    |       +==============+===============+                        |
    |                      v                                        |
    |          AddressEnrichmentService                             |
    |           (idempotency + orchestration)                       |
    |                      |                                        |
    |         +--- CascadeOrchestrator ---+                         |
    |         |            |              |                         |
    |         v            v              v                         |
    |     libpostal    swift-crf        llm        (future plugin)  |
    |     (gRPC)       (gRPC)         (HTTP)                        |
    |         |            |              |                         |
    |         +============+==============+                         |
    |                      |                                        |
    |          FieldMerger (per-field confidence voting)             |
    |                      |                                        |
    |          StructuredAddress + per-field provenance              |
    |                      |                                        |
    |   +------------------+------------------+                     |
    |   v                                     v                     |
    |  adapter-prowide            adapter-oracle-app                |
    |  (ISO 20022 PstlAdr)        (TFPM_ADDR_ENRICH schema)        |
    |                                                               |
    |  ComplianceRouter -----> compliance routing decisions         |
    +--------------------------------------------------------------+
                                       |
                                       v
                       Oracle: TFPM_ADDR_ENRICH schema
                       +----------------------------------+
                       | IDEMPOTENCY_KEYS  (dedup)        |
                       | STRUCTURING_RESULTS (output)     |
                       | EXCEPTION_QUEUE (human review)   |
                       | AUDIT_LOG (append-only)           |
                       | FIELD_ATTRIBUTIONS (per-field)    |
                       | VALIDATION_FEEDBACK (corrections) |
                       | ACCURACY_SAMPLES (golden set)     |
                       | COMPLIANCE_ROUTING (decisions)    |
                       +----------------------------------+
```

## Module Structure

```
tfpm-address-enrichment/
+-- domain/                     Pure Java: interfaces, records, sealed types.
|                               Zero Spring dependencies. The contracts that
|                               bind everything together.
|
+-- adapters/
|   +-- adapter-libpostal/      gRPC client to the libpostal sidecar container.
|   |                           Statistically parses addresses using OpenStreetMap
|   |                           training data. Best for Western-format addresses.
|   |
|   +-- adapter-swift-crf/      Placeholder for SWIFT's AI Address Structuring Model.
|   |                           Disabled by default; activates when the model is
|   |                           downloaded and IS&C-cleared. Zero code changes needed.
|   |
|   +-- adapter-llm/            Two LLM client implementations:
|   |                           - JpmcInternalGatewayLlmClient (OAuth2, internal network)
|   |                           - OpenAiCompatibleLlmClient (Ollama, OpenAI, vLLM)
|   |                           Country-specific prompts drive field extraction.
|   |
|   +-- adapter-prowide/        Maps StructuredAddress to/from ISO 20022 PostalAddress27
|   |                           using the Prowide library. Hides Prowide types from
|   |                           the rest of the codebase.
|   |
|   +-- adapter-oracle-legacy/  Read-only access to legacy address data via jOOQ.
|   |                           Uses TFPM_LEGACY_RO user (SELECT-only privileges).
|   |                           ArchUnit enforces: no INSERT/UPDATE/DELETE anywhere.
|   |
|   +-- adapter-oracle-app/     Read-write persistence on TFPM_ADDR_ENRICH schema.
|                               Idempotency store, result persistence, exception queue,
|                               audit log. Uses TFPM_ADDR_ENRICH_APP user (DML-only).
|
+-- inbound/
|   +-- inbound-http/           REST controller: POST /api/v1/enrich
|   |                           Validates input, maps to EnrichmentRequest, returns
|   |                           structured JSON response with per-field confidence.
|   |
|   +-- inbound-kafka/          Kafka listener with cooperative-sticky assignor.
|   |                           Manual ack only after Oracle commit. DLT routing
|   |                           for non-retryable errors.
|   |
|   +-- inbound-mq/             IBM MQ JMS listener. Client-ack after Oracle commit.
|                               No XA — idempotency table provides exactly-once.
|
+-- app/                        Spring Boot composition root. Wires everything together.
|                               Contains CascadeOrchestrator, FieldMerger, and
|                               AddressEnrichmentServiceImpl. No concrete adapter
|                               references — only domain interfaces.
|
+-- archunit-tests/             14 architectural rules enforced at build time:
|                               module boundaries, thread-safety, shadow-mode invariant,
|                               plugin contract, forbidden patterns.
|
+-- integration-tests/          End-to-end tests with Testcontainers:
|                               - Multi-container idempotency (3 replicas, 1 Oracle row)
|                               - Golden-set accuracy (14 fixtures, 8 countries)
|                               - 1000-message stress test
|
+-- infra/
|   +-- liquibase/              8 tables with INTERVAL partitioning, LOCAL indexes,
|   |                           and strict grant separation (DDL vs DML users).
|   +-- docker/                 Local dev: Oracle Free 23ai, Kafka KRaft, IBM MQ 9.4,
|                               WireMock (LLM gateway stub).
|
+-- proto/                      gRPC contract: structurer.proto v1. Single source of
|                               truth for sidecar communication.
+-- docs/                       10 design documents covering architecture, country
                                strategy, compliance, accuracy, retry semantics,
                                data sources, LLM integration, and the 15-day plan.
```

## How the Enrichment Pipeline Works End-to-End

Here is the complete flow for a single address enrichment request:

```
1. RECEIVE
   An address arrives via HTTP POST, Kafka message, or IBM MQ message.
   The channel adapter normalizes it to an EnrichmentRequest:
     { correlationId, sourceChannel, rawAddress, countryHint, locale }

2. DEDUPLICATE
   Compute idempotency key: SHA-256(canonical(rawAddress) + sourceChannel)
   INSERT INTO IDEMPOTENCY_KEYS — if ORA-00001, return cached prior result.

3. ROUTE
   CountryRouter checks: for this country, which structurers should run?
   Example: skip libpostal for Chinese addresses (known-weak), go straight to LLM.

4. CASCADE
   For each structurer in cascade order [libpostal, swift-crf, llm]:
     a. Call the structurer (gRPC or HTTP)
     b. Receive per-field results with raw confidence scores
     c. Check early-exit: if all required fields above 0.92 threshold, stop early

5. CALIBRATE
   Each structurer's ConfidenceCalibrator normalizes raw scores to [0,1]:
     libpostal log-likelihood -0.3  -->  calibrated 0.88
     LLM self-reported 0.95         -->  calibrated 0.91

6. MERGE
   FieldMerger votes per field: highest calibrated confidence wins.
   Ties broken by cascade order (earlier structurer preferred).
   Result: StructuredAddress with per-field provenance.

7. PERSIST
   INSERT INTO STRUCTURING_RESULTS (fields_json, structurer_trace, confidence)
   UPDATE IDEMPOTENCY_KEYS SET result_ref = :new_result_id

8. CLASSIFY
   - Confidence >= 0.70 AND all mandatory fields present  -->  SUCCESS
   - Confidence < 0.70 OR mandatory fields missing        -->  REQUIRES_REVIEW
   - No usable fields at all                               -->  UNSTRUCTURABLE
   If REQUIRES_REVIEW or UNSTRUCTURABLE: INSERT INTO EXCEPTION_QUEUE

9. COMPLIANCE
   ComplianceRouter evaluates four axes (field confidence, overall confidence,
   country risk, pattern triggers). Logs the decision; in shadow mode, does not
   block payment flow.

10. RESPOND
    Return EnrichmentResult to the channel adapter, which maps it to:
    - HTTP: JSON response with 200/422
    - Kafka: acknowledge the message (manual ack)
    - MQ: acknowledge the message (client ack)
```

## Country-Specific Handling

Address formats vary dramatically by country. The service handles this through:

| Country | Key Challenge | Mitigation |
|---------|--------------|------------|
| **UAE (AE)** | No standard roads; PO Boxes; free zones (DIFC, JAFZA); tower/office chains | Pre-processing normalizations (27 regex patterns), emirate-as-city mapping |
| **China (CN)** | Reverse order (province-city-district-road-number); multi-script; libpostal fails | Skip libpostal via CountryRouter, LLM-only with Chinese-specific prompts |
| **Singapore (SG)** | Block-Street-Unit format; HDB patterns; 6-digit postal codes | Pattern matching for HDB notation, unit extraction |
| **Hong Kong (HK)** | No postal codes; floor/unit patterns; district mapping | District-to-subdivision mapping, floor/unit normalization |
| **UK (GB)** | Complex postcode format; building names common; flat/apartment numbering | Postcode regex validation, building name extraction |
| **US** | State abbreviations; ZIP+4; suite/apt notation | Abbreviation expansion, ZIP code validation |
| **Germany (DE)** | Straße/Str. variants; PLZ format; Umlauts | German normalization rules, PLZ validation |
| **Switzerland (CH)** | Multi-language (DE/FR/IT/RM); canton codes | Language detection, canton normalization |

Accuracy targets: >= 80% per-field for Tier-0 countries, >= 75% for Tier-1, >= 65% for Tier-2.

## Thread Safety Model

The service runs as N replicas with dozens of concurrent threads per replica (HTTP worker threads, Kafka consumer threads, MQ listener threads). Every singleton bean on the request path must be thread-safe.

The `@ThreadSafe` annotation + ArchUnit enforcement guarantees:
- All instance fields are `final`
- All field types are immutable, atomic, or concurrent collections
- No `synchronized` blocks (use concurrent collections instead)
- No mutable static state
- No `ThreadLocal` for application data

This is verified at **compile time**, not runtime — unsafe code fails the build.

## Five Retry Layers

Failures are handled by five distinct retry layers, each with one job:

```
Layer 5: Channel-native
  Kafka rewind, JMS redeliver, HTTP client retry.
  Infinite (Kafka) or configured (MQ: 3 redeliveries).

Layer 4: Service-level
  Idempotency-race result-load retry. Max 3 attempts, <= 260ms.
  Handles: another replica is mid-cascade, result not yet visible.

Layer 3: Persistence
  Spring @Retryable on Oracle deadlock / transient errors. Max 3 attempts.
  Handles: row-level contention on busy partitions.

Layer 2: Per-structurer
  Resilience4j @Retry. Max 2 attempts. Falls back to empty result.
  Handles: sidecar flapping, transient 5xx from LLM gateway.

Layer 1: Per-RPC
  gRPC service-config / WebClient.retryWhen. Max 2 attempts.
  Handles: network blips, individual request timeouts.
```

Every layer obeys `EnrichmentError.isRetryable()` — the single decision point. Non-retryable errors (bad input, auth failure, schema mismatch) are never retried at any layer.

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| **`Result<T>` instead of exceptions** | Fallible operations return `Result.Success` or `Result.Failure` with `EnrichmentError`. Callers handle both via Java 21 pattern matching. No try/catch ceremony; retry layers inspect `error.isRetryable()`. |
| **INSERT-first-catch-ORA-00001** | Idempotency without race conditions. Never SELECT-then-INSERT (two replicas could both see "not found"). Oracle's unique constraint is the single coordination point across all replicas. |
| **Two Oracle pools, two users** | `TFPM_LEGACY_RO` (SELECT-only, 30 connections) and `TFPM_ADDR_ENRICH_APP` (DML-only, 50 connections). Even a code bug can't write to legacy or DDL the new schema — the database rejects it. |
| **No XA for IBM MQ** | XA at JPMC is operationally expensive. Idempotency table + JMS client-ack provides exactly-once semantics without XA coordinator overhead. |
| **Cooperative-sticky Kafka assignor** | Minimizes partition rebalance disruption; partitions stick to the same consumer across rolling deploys. |
| **Per-field merging, not per-result** | Each structurer contributes its strengths. libpostal wins street names (0.92); LLM wins country (0.99). The merged result is strictly better than any single structurer. |
| **`@Calibrated` annotation** | Every structurer must have a `ConfidenceCalibrator`. Comparing raw libpostal log-likelihoods against LLM probabilities would produce nonsensical merges. Calibration normalizes to a common scale. |
| **Plugin contract** | Adding a new structurer = new Maven module + `@Component` + config. Zero changes to CascadeOrchestrator, FieldMerger, or any existing code. ArchUnit enforces this. |
| **Oracle-only persistence** | No Mongo, Redis, ElasticSearch, or any new infrastructure. Reuses the existing JPMC TFPM Oracle stack, avoiding intake friction and ops learning curve. |
| **Shadow mode as architectural invariant** | Not just a flag — ArchUnit enforces that no code path writes to `payments.*` topics or legacy schema. The shadow guarantee is structural, not behavioral. |

## Quickstart

```bash
# Prerequisites: Java 21, Maven 3.9+, Docker

# 1. Start local infrastructure (Oracle, Kafka, MQ, WireMock)
make up && make wait && make migrate

# 2. Build and run all tests (238 tests)
mvn clean test

# 3. Run the application
mvn -pl app spring-boot:run

# 4. Enrich an address
curl -s -X POST http://localhost:8080/api/v1/enrich \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: test-001' \
  -d '{
    "rawAddress": "Office 1204, Emirates Towers, Sheikh Zayed Road, Dubai, UAE",
    "countryHint": "AE"
  }' | jq .

# 5. Run integration tests (requires Docker)
mvn verify -Pit
```

## Testing Strategy

| Layer | Tests | Framework | What It Validates |
|-------|-------|-----------|-------------------|
| **Domain unit** | 196 | JUnit 5 + AssertJ + jqwik | Records, sealed types, validation, `Result<T>` functor laws, defensive copying, null rejection |
| **App unit** | 19 | JUnit 5 + Mockito | CascadeOrchestrator (early exit, country routing, failure resilience, 100-thread concurrency), FieldMerger (per-field voting, tie-breaking), ServiceImpl (all 4 outcomes, duplicate handling) |
| **Adapter unit** | 12 | JUnit 5 + Mockito | SHA-256 key computation, canonical normalization, Prowide round-trip mapping, Kafka/MQ ack semantics |
| **Inbound unit** | 11 | MockMvc + Mockito | HTTP response codes, JSON structure, correlation ID propagation, error handling |
| **ArchUnit** | 14 rules | ArchUnit 1.3 | Module boundaries, thread-safety contract, shadow-mode invariant, plugin contract, forbidden patterns |
| **Integration** | - | Testcontainers + Awaitility | Multi-container idempotency (3 replicas, 1 result), 1000-message stress, golden-set accuracy (14 fixtures, 8 countries) |

**Total: 238 unit tests, all green.**

## Configuration Reference

Key properties in `app/src/main/resources/application.yml`:

| Property | Default | Description |
|----------|---------|-------------|
| `enrichment.cascade.order` | `[libpostal, swift-crf, llm]` | Structurer invocation order |
| `enrichment.cascade.early-exit-threshold` | `0.92` | Stop cascade early if required fields exceed this |
| `enrichment.cascade.review-threshold` | `0.70` | Results below this go to exception queue |
| `enrichment.libpostal.enabled` | `true` | Enable libpostal gRPC structurer |
| `enrichment.libpostal.endpoint` | `localhost:50051` | libpostal sidecar address |
| `enrichment.libpostal.timeout-ms` | `500` | Per-call timeout |
| `enrichment.swift-crf.enabled` | `false` | Enable SWIFT CRF (stub until model downloaded) |
| `enrichment.llm.enabled` | `true` | Enable LLM structurer |
| `enrichment.llm.client` | `jpmc-internal-gateway` | Which LLM client bean to use |
| `enrichment.kafka.input-topic` | `address-enrichment-input` | Kafka input topic |
| `enrichment.kafka.enabled` | `false` | Enable Kafka listener |
| `enrichment.mq.input-queue` | `TFPM.PAYMENT.IN` | IBM MQ input queue |
| `enrichment.test.stub.enabled` | `false` | Enable deterministic stub structurer (testing only) |

## Oracle Schema

8 tables in `TFPM_ADDR_ENRICH` schema, all with INTERVAL partitioning:

| Table | Purpose | Partitioning |
|-------|---------|-------------|
| `IDEMPOTENCY_KEYS` | Cross-replica deduplication via unique constraint | Daily |
| `STRUCTURING_RESULTS` | Enrichment output (FIELDS_JSON CLOB, structurer trace) | Weekly |
| `EXCEPTION_QUEUE` | Low-confidence cases for human review (SKIP LOCKED claim) | Weekly |
| `AUDIT_LOG` | Append-only event trail | Monthly |
| `FIELD_ATTRIBUTIONS` | Denormalized per-field provenance for accuracy reporting | Weekly |
| `VALIDATION_FEEDBACK` | Operator corrections from maker/checker UI | Monthly |
| `ACCURACY_SAMPLES` | Stratified daily samples for human review (4:4:2 ratio) | Monthly |
| `COMPLIANCE_ROUTING` | All routing decisions (Bypass/Route/Block) | Weekly |

## Operational Notes

- **Health**: `/actuator/health` — checks both Oracle pools, Kafka broker, MQ queue manager
- **Metrics**: `/actuator/prometheus` — 7 key metrics: `address.enrichment.processed`, `confidence`, `latency`, `cascade.fallback`, `exceptions`, `idempotency.duplicate`, `oracle.pool`
- **Graceful shutdown**: `server.shutdown=graceful` with 30s drain period
- **Replicas**: Designed for 3-4; idempotency table is the only cross-replica coordination
- **Shadow mode**: Compile-time enforced — ArchUnit fails the build if any code path writes to production topics or legacy schema
- **Backfill**: Legacy Oracle addresses can be streamed via `LegacyAddressReader.readAll()` at ~32K addresses/sec across 32 partitioned workers

## Status

| Component | Status |
|-----------|--------|
| Domain contracts + value objects | Complete (15 files) |
| CascadeOrchestrator + FieldMerger | Complete with tests |
| AddressEnrichmentServiceImpl | Complete with tests |
| libpostal adapter | Implemented (needs sidecar for runtime) |
| SWIFT CRF adapter | Stub (awaiting model download + IS&C clearance) |
| LLM adapter (2 clients) | Complete |
| Prowide ISO 20022 mapper | Complete with round-trip tests |
| Oracle persistence | Complete |
| HTTP inbound | Complete with MockMvc tests |
| Kafka inbound | Complete with ack-semantics tests |
| IBM MQ inbound | Complete with ack-semantics tests |
| ArchUnit enforcement | 14 rules, all passing |
| Unit tests | 238 tests, all green |
| Integration tests | Skeleton ready (needs Docker for runtime) |
| Golden-set accuracy fixtures | 14 fixtures across 8 Tier-0 countries |
| Production cutover | NOT IN SCOPE (shadow mode only) |

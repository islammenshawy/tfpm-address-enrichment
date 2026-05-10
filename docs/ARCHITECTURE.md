# ARCHITECTURE.md

This document explains *why* the system is shaped the way it is. CLAUDE.md
tells you the rules; this document tells you the reasoning. Read this once
end-to-end before starting Day 0; refer back when a design decision feels
arbitrary.

---

## 1. The big picture

```
                       ┌─────────────────────────────────────┐
                       │  PROD PAYMENT PATH (untouched)     │
                       │  TPS → Oracle → Kafka → MX → SWIFT │
                       └────────────────┬────────────────────┘
                                        │
                            tee / CDC / corporate file copy
                                        │
                                        ▼
       ┌────────────────────────────────────────────────────────────┐
       │  Inbound channels (each replica handles all three)         │
       │   ┌─────────┐    ┌──────────┐    ┌───────────┐            │
       │   │ HTTP    │    │ Kafka    │    │ IBM MQ    │            │
       │   └────┬────┘    └────┬─────┘    └─────┬─────┘            │
       │        └──────────────┼──────────────────┘                 │
       │                       ▼                                    │
       │           AddressEnrichmentService                         │
       │                       │                                    │
       │                       ▼                                    │
       │     ┌──────────  CascadeOrchestrator  ──────────┐          │
       │     │              │              │              │         │
       │     ▼              ▼              ▼              ▼         │
       │  libpostal     swift-crf         llm        (future)       │
       │  (gRPC)         (gRPC)         (HTTP)      (any plugin)    │
       │     │              │              │                        │
       │     └──────────────┴──────────────┘                        │
       │                       │                                    │
       │                       ▼                                    │
       │           FieldMerger + Calibrators                        │
       │                       │                                    │
       │                       ▼                                    │
       │           StructuredAddress + provenance                   │
       │                       │                                    │
       │   ┌───────────────────┴──────────────────┐                 │
       │   ▼                                       ▼                │
       │ adapter-prowide              adapter-oracle-app            │
       │ (PstlAdr build)              (writes to TFPM_ADDR_ENRICH)  │
       │                                                            │
       │   replica 1                replica 2                replica 3
       └────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
                          Oracle: TFPM_ADDR_ENRICH schema
                          (idempotency + results + exceptions + audit)
```

**Key invariants** (also enforced by ArchUnit):

- The prod payment path is never modified.
- The legacy Oracle schema is read-only from this service.
- All writes go to `TFPM_ADDR_ENRICH`, a new schema with its own user.
- All three input channels normalize to one service entry point.
- The cascade is a list of plugins; adding one is a pure addition.

---

## 2. Module structure and dependency rules

```
                        ┌────────────────┐
                        │   inbound-*    │
                        │ (http,kafka,mq)│
                        └───────┬────────┘
                                │ depends on
                                ▼
                        ┌────────────────┐
                        │      app       │
                        │ (orchestrator, │
                        │  service impl) │
                        └───────┬────────┘
                                │ depends on
                                ▼
                        ┌────────────────┐
                        │     domain     │ ← interfaces, value objects
                        │  (zero deps)   │   pure Java, no Spring
                        └────────▲───────┘
                                 │ implements
              ┌──────────────────┼──────────────────┐
              │                  │                  │
      ┌───────────────┐  ┌───────────────┐  ┌──────────────┐
      │  adapters/*   │  │  adapters/*   │  │  adapters/*  │
      │  (libpostal,  │  │  (prowide)    │  │  (oracle-*)  │
      │   swift-crf,  │  │               │  │              │
      │   llm)        │  │               │  │              │
      └───────────────┘  └───────────────┘  └──────────────┘
```

### Why these boundaries

- **`domain`** is pure Java. Zero Spring, zero adapter deps. This means
  the contracts (interfaces, value objects, the cascade algorithm in the
  abstract) can be unit-tested in milliseconds without any container.
  It also means refactoring an adapter — say, swapping libpostal for a
  vendor product — never ripples into domain code.
- **`adapters/*`** sit *behind* domain interfaces. Each one is replaceable.
  Each one has its own POM, its own test suite, its own dependencies.
  When the SWIFT model is downloaded and the real adapter is wired up,
  it goes in `adapter-swift-crf` and nothing else changes.
- **`inbound/*`** sit *in front of* the service. Each channel is independent
  — Kafka has no idea HTTP exists. They share nothing except the
  `EnrichmentRequest` value object and the service interface.
- **`app`** is the only place that wires everything together with Spring.
  It's the composition root.

### What lives where

| Module | Contains | Cannot reference |
|---|---|---|
| `domain` | Interfaces, records, enums, marker annotations | Anything except SLF4J api |
| `adapters/adapter-libpostal` | `LibpostalAddressStructurer`, gRPC stubs, Resilience4j config | Other adapters, inbound, app |
| `adapters/adapter-swift-crf` | `SwiftCrfAddressStructurer` (stub today), gRPC stubs | Other adapters, inbound, app |
| `adapters/adapter-llm` | `LlmAddressStructurer`, WebClient config, prompt templates | Other adapters, inbound, app |
| `adapters/adapter-prowide` | `PstlAdrMapper`, `MxMessageEnricher` | Inbound, other structurer adapters |
| `adapters/adapter-oracle-legacy` | `JooqLegacyAddressReader`, generated jOOQ classes for legacy | Any write operation, app, inbound |
| `adapters/adapter-oracle-app` | `JooqIdempotencyStore`, `JooqExceptionQueue`, `JooqResultsRepository`, generated jOOQ for `TFPM_ADDR_ENRICH` | Legacy schema tables, inbound |
| `inbound/inbound-http` | `EnrichmentController`, DTOs, exception mappers | Other inbound modules, adapters |
| `inbound/inbound-kafka` | `EnrichmentKafkaListener`, error handler | Other inbound modules, adapters |
| `inbound/inbound-mq` | `EnrichmentJmsListener`, MQ connection factory | Other inbound modules, adapters |
| `app` | `CascadeOrchestrator`, `FieldMerger`, `AddressEnrichmentServiceImpl`, `Application.java`, all `@Configuration` | Concrete adapter classes (only via interface) |

---

## 3. The plugin contract for structurers

This is the single most important design decision in the codebase. Every
new structurer plugs in via the same contract:

```java
public interface AddressStructurer {
    String name();                       // unique, stable
    Set<AddressField> supportedFields(); // capability advertisement
    StructuringResult structure(RawAddress raw);
}
```

The cascade orchestrator only depends on `List<AddressStructurer>`.
Spring injects all beans of this type, in the order declared in
`enrichment.cascade.order`. Adding a new structurer means:

1. Create a new `adapters/adapter-<name>` module.
2. Implement `AddressStructurer`.
3. Annotate `@Component`, `@ThreadSafe`, `@Calibrated`,
   `@ConditionalOnProperty(name = "enrichment.<name>.enabled")`.
4. Provide a `ConfidenceCalibrator` (start with identity).
5. Wrap the runtime in a sidecar speaking `proto/structurer.proto` v1.
6. Add config block under `enrichment.<name>` in `application.yml`.
7. Add to `enrichment.cascade.order` in the desired position.

No other code changes. No `if/else` branches added anywhere. No special
casing in the orchestrator. The cascade just sees one more bean.

### The merger algorithm

The cascade does NOT short-circuit on the first non-empty result. Each
structurer runs (subject to early-exit thresholds) and produces a
`StructuringResult` with per-field `FieldValue` (value + raw confidence).

The `FieldMerger` then **votes per field**:

```java
for each AddressField:
    pick the FieldValue with the highest CALIBRATED confidence
    across all structurers that returned that field
```

Why per-field, not per-result:
- libpostal might be highly confident on `STRT_NM` but uncertain on `CTRY`.
- SWIFT CRF might be very confident on `CTRY` but doesn't return `STRT_NM` at all.
- LLM might be moderately confident on everything but win the long tail.

Per-field merging means every structurer contributes its strengths and
the result strictly improves as more structurers join the cascade.

### Calibration matters

Different structurers report confidence on different scales:
- libpostal: log-likelihood (typically -2 to 0)
- CRF: marginal probability (0 to 1)
- LLM: whatever you prompt for

Comparing raw scores would give nonsense. The `ConfidenceCalibrator`
per structurer normalizes raw to 0..1 *calibrated* probability of
correctness, learned against the accuracy harness's golden set.

Day 1: identity calibrators (raw passed through).
Week 2+: real calibrators tuned per structurer per country.

---

## 4. Multi-channel input pattern

Three channels, one service entry. The channels are thin adapters; all
business logic is in the service.

```java
// HTTP
@PostMapping("/enrich")
public EnrichmentResponse enrich(@RequestBody EnrichmentDto dto) {
    var req = HttpDtoMapper.toRequest(dto);
    var result = service.enrich(req);
    return HttpDtoMapper.toResponse(result);
}

// Kafka
@KafkaListener(topics = "${enrichment.kafka.input-topic}")
public void onMessage(ConsumerRecord<String, AddressMessage> rec, Acknowledgment ack) {
    var req = KafkaMessageMapper.toRequest(rec);
    var result = service.enrich(req);
    publisher.send(result);
    ack.acknowledge();
}

// IBM MQ via JMS
@JmsListener(destination = "${enrichment.mq.input-queue}")
public void onMessage(Message message) throws JMSException {
    var req = JmsMessageMapper.toRequest(message);
    var result = service.enrich(req);
    jmsTemplate.convertAndSend(outputQueue, result);
    // No explicit ack: JMS auto-ack after successful return
}
```

All three call `service.enrich(req)`. The service:

1. Computes idempotency key (SHA-256 of canonical input).
2. Tries `INSERT INTO IDEMPOTENCY_KEYS` first.
   - If `ORA-00001` (duplicate key): returns the cached prior result.
   - If success: proceeds to cascade.
3. Runs cascade through the structurers.
4. Persists result + idempotency mapping in a single Oracle transaction.
5. Returns.

This pattern means **at-least-once delivery from the channel + Oracle
unique constraint = exactly-once processing.** It works across replicas
because the unique constraint is enforced by the database, not the
application.

---

## 5. Thread safety in detail

The service runs as N replicas. Within each replica, multiple threads
process concurrently:
- HTTP: Tomcat worker threads (default 200, bounded)
- Kafka: one thread per consumer in the consumer group, partitioned work
- JMS: pool of `MessageListener` threads (typically 10-25)

This means at any moment, dozens of threads in one replica may be
inside `service.enrich(...)` simultaneously. Singletons must be safe.

### What "thread-safe" means in this codebase

A class is thread-safe if and only if:

1. All instance fields are `final`.
2. All instance fields are one of:
   - A primitive
   - An immutable value (`String`, `Instant`, `BigDecimal`, records, enums)
   - An immutable collection (`List.of(...)`, `Map.of(...)`, `Set.of(...)`)
   - A thread-safe collection (`ConcurrentHashMap`, `CopyOnWriteArrayList`)
   - An atomic primitive (`AtomicLong`, `LongAdder`, etc.)
   - Another thread-safe object
3. No mutable static state.
4. No `ThreadLocal` for application data.
5. Methods do not depend on call ordering across threads.

The `@ThreadSafe` annotation is a compile-time marker; ArchUnit verifies
the rules at build time.

### Why no `synchronized`

`synchronized` is forbidden because it suggests a mutable shared state
that should have been a thread-safe collection. If you genuinely need
mutual exclusion, use a `ReentrantLock` with a comment explaining why a
concurrent collection wouldn't work. (Almost always: it would.)

### Per-component thread-safety notes

| Component | Notes |
|---|---|
| `CascadeOrchestrator` | Stateless. Fields are final references to other thread-safe beans. |
| `FieldMerger` | Stateless pure function. No fields except final calibrator registry. |
| `*AddressStructurer` | Stateless. gRPC channels are thread-safe and shared. |
| `ConfidenceCalibrator` | Stateless after construction. Calibration tables loaded once, immutable. |
| `*Mapper` (MapStruct) | Stateless. Generated code has no fields. |
| `JooqIdempotencyStore` | Stateless. `DSLContext` is thread-safe. |
| `JooqExceptionQueue` | Stateless. Uses `FOR UPDATE SKIP LOCKED` for concurrent claim. |
| `EnrichmentController` | Stateless. Final reference to service. |
| Spring Kafka `Listener` | Stateless. Listener container manages threads. |
| Spring JMS `Listener` | Stateless. Container manages thread pool. |
| `JAXBContext` | Thread-safe singleton (`@Bean`). |
| `Marshaller` | NOT thread-safe — created per-call inside mapper methods. |
| `ObjectMapper` | Thread-safe singleton (`@Bean`); never reconfigured after startup. |
| Prowide `PostalAddress*` | Mutable POJOs — created fresh per call inside mapper methods. |
| gRPC `ManagedChannel` | Thread-safe; one per sidecar; closed on shutdown. |
| `WebClient` | Thread-safe; bounded connection pool. |
| HikariCP | Thread-safe pool; two pools for read/write separation. |

---

## 6. Oracle persistence design

### Two pools, two users

```
┌─ TFPM_LEGACY_RO ───────────────────────┐
│ GRANT SELECT ON TFPM_LEGACY.PARTY     │
│ GRANT SELECT ON TFPM_LEGACY.ADDRESS   │
│ ... no other privileges                │
└────────────────────────────────────────┘
        ▲
        │ Read-only HikariCP pool (max 30)
        │ used by adapter-oracle-legacy ONLY
        │
        │
┌─ TFPM_ADDR_ENRICH_APP ─────────────────┐
│ GRANT SELECT, INSERT, UPDATE, DELETE  │
│   ON TFPM_ADDR_ENRICH.* tables        │
│ NO DDL grants                         │
│ NO grants on legacy schema            │
└────────────────────────────────────────┘
        ▲
        │ Read-write HikariCP pool (max 50)
        │ used by adapter-oracle-app ONLY
```

Two pools enforce, at the database level, that the legacy schema is
read-only from this service. Even if a future bug tries to UPDATE legacy,
it fails with insufficient privileges.

### Schema (`TFPM_ADDR_ENRICH`)

Four tables. Migrations in `infra/liquibase/changelog/`.

#### `IDEMPOTENCY_KEYS`

Race-free deduplication across replicas. Pattern:

```sql
INSERT INTO IDEMPOTENCY_KEYS (IDEM_KEY, SOURCE_CHANNEL, SOURCE_REF, RESULT_REF)
VALUES (?, ?, ?, ?);
-- if ORA-00001: this message was already processed; load prior result
```

Partitioned by day; old partitions purged after 30 days.

#### `STRUCTURING_RESULTS`

The output. One row per successfully enriched address. JSON columns
hold the per-field map and the per-structurer trace, so adding a new
structurer doesn't require schema changes.

#### `EXCEPTION_QUEUE`

Low-confidence cases for human review. Multiple makers claim work
concurrently using:

```sql
SELECT * FROM EXCEPTION_QUEUE
WHERE STATUS = 'OPEN'
ORDER BY CREATED_AT
FETCH FIRST 10 ROWS ONLY
FOR UPDATE SKIP LOCKED;
```

`SKIP LOCKED` means concurrent maker sessions don't block each other —
each gets a different batch of work. This is how you build a queue on
top of Oracle without a separate broker.

`VERSION` column for optimistic locking on the resolution write-back.

#### `AUDIT_LOG`

Append-only. Inserted by app code, never by triggers (triggers create
hidden coupling). Partitioned by month; retained per JPMC retention
policy (typically 7 years for payment-adjacent audit).

### Why not Oracle queue features (AQ)?

Oracle Advanced Queueing exists and would technically work for the
exception queue. We don't use it because:
1. It's an extra surface area for ops to learn.
2. `FOR UPDATE SKIP LOCKED` on a regular table is sufficient at
   expected volumes (low thousands of exceptions per day).
3. Schema stays portable — the pattern works on any RDBMS that
   supports `SKIP LOCKED` (PostgreSQL, etc.) if the platform direction
   shifts.

---

## 7. Idempotency in detail

The idempotency key is `SHA-256(canonicalize(rawAddress) || sourceChannel)`.
Canonicalization: trim, collapse whitespace, lowercase. NOT the full
`EnrichmentRequest` — we want the same address from the same channel
to dedupe regardless of correlation id.

The flow:

```
1. compute key
2. INSERT INTO IDEMPOTENCY_KEYS ... → success path
   - run cascade
   - INSERT INTO STRUCTURING_RESULTS ... RETURNING RESULT_ID
   - UPDATE IDEMPOTENCY_KEYS SET RESULT_REF = :id WHERE IDEM_KEY = :key
   - commit
   - return result
3. INSERT INTO IDEMPOTENCY_KEYS ... → ORA-00001
   - SELECT RESULT_REF FROM IDEMPOTENCY_KEYS WHERE IDEM_KEY = :key
   - if RESULT_REF is NULL: another replica is processing — wait briefly
     and retry the SELECT (bounded backoff, max 3 attempts)
   - SELECT * FROM STRUCTURING_RESULTS WHERE RESULT_ID = :ref
   - return cached result
```

Step 3's brief wait handles the rare case where another replica won the
INSERT race but hasn't yet committed the result. In practice this is
sub-millisecond; the bounded retry keeps the worst case under 100ms.

---

## 8. Observability

Every stage emits:
- `traceId`: propagated end-to-end via OpenTelemetry through gRPC,
  HTTP, Kafka headers, JMS properties.
- Structured JSON log: `{"timestamp", "level", "logger", "traceId",
  "channel", "stage", "message", "fields"}`.
- Micrometer metric: a counter or histogram, tagged by structurer,
  channel, country, outcome.

Key metrics:

| Metric | Type | Tags |
|---|---|---|
| `address.enrichment.processed` | Counter | channel, outcome |
| `address.enrichment.confidence` | Histogram | structurer, field, country |
| `address.enrichment.latency` | Timer | channel, structurer |
| `address.enrichment.cascade.fallback` | Counter | from_structurer, to_structurer |
| `address.enrichment.exceptions` | Counter | reason, country |
| `address.enrichment.idempotency.duplicate` | Counter | channel |
| `address.enrichment.oracle.pool` | Gauge | pool_name (auto from HikariCP) |

Alerts go on:
- Cascade fallback rate above expected baseline (signals upstream degradation)
- Exception rate above expected baseline (signals model drift)
- Oracle pool exhaustion (signals load or leak)
- Sidecar circuit breaker open (signals downstream failure)

---

## 9. Future-proofing: when SWIFT CRF arrives

The `adapter-swift-crf` module exists today as a stub:

```java
@Component
@ThreadSafe
@Calibrated
@ConditionalOnProperty(name = "enrichment.swift-crf.enabled", havingValue = "true")
public class SwiftCrfAddressStructurer implements AddressStructurer {
    @Override public String name() { return "swift-crf"; }
    @Override public Set<AddressField> supportedFields() {
        return Set.of(AddressField.TWN_NM, AddressField.CTRY);
    }
    @Override public StructuringResult structure(RawAddress raw) {
        throw new UnsupportedOperationException("...");
    }
}
```

Off by default. To activate when the model is downloaded and IS&C-cleared:

1. Wrap the SWIFT Python/PyTorch entry point in a gRPC sidecar speaking
   `proto/structurer.proto` v1.
2. Replace the body of `structure(...)` with a real gRPC client call
   (clone the pattern from `LibpostalAddressStructurer`).
3. Implement `SwiftCrfConfidenceCalibrator` (start with identity).
4. Add `enrichment.cascade.order: [libpostal, swift-crf, llm]` in
   `application.yml`.
5. Set `enrichment.swift-crf.enabled: true`.
6. Run accuracy harness; confirm no per-country regression.
7. Promote.

Estimated time when bits are ready: half a day.

---

## 10. What's deliberately not in scope

These have been considered and rejected for v1:

- **Maker/checker UI.** Reuse the existing TFPM exception queue UI.
  Backend exposes the queue via existing patterns; no new frontend.
- **Real-time streaming via reactive WebFlux.** The cascade is bounded
  to <500ms end-to-end; classic blocking with bounded thread pools is
  simpler and meets the SLO. WebFlux can come later if volume demands.
- **Distributed cache for calibrator tables.** Tables are small (KB),
  loaded at startup, identical across replicas. In-memory immutable
  copies are simpler than any cache.
- **Oracle Advanced Queueing.** `FOR UPDATE SKIP LOCKED` on a regular
  table is sufficient and portable.
- **XA transactions across MQ + Oracle.** XA at JPMC is operationally
  expensive. Idempotency table + JMS auto-ack provides exactly-once
  semantics without XA.
- **Auto-correction back to source systems.** Out of scope. We write
  to a separate schema; closing the loop to TPS/legacy is a
  follow-on workstream.
- **Production cutover.** This is a shadow-mode service. Cutover is a
  separate program with its own change control.

---

## 11. Decision log

| Date | Decision | Rationale |
|---|---|---|
| Day 0 | Java 21 + Spring Boot 3.3 | Matches TFPM platform direction |
| Day 0 | jOOQ over JPA | Legacy schema is not ORM-friendly; jOOQ generates from DDL |
| Day 0 | Oracle-only persistence | JPMC infra constraint; avoids Mongo intake friction |
| Day 0 | Two HikariCP pools | Read/write separation enforced at DB level |
| Day 0 | Plugin contract for structurers | Future SWIFT/vendor models drop in cleanly |
| Day 0 | Per-field merging, not per-result | Structurers contribute strengths; cascade strictly improves |
| Day 0 | gRPC for sidecars | Stronger contract than REST; mature Java tooling |
| Day 0 | HTTP for LLM gateway | Existing internal gateway is HTTP, not gRPC |
| Day 0 | INSERT-first idempotency | Race-free, no SELECT-then-INSERT window |
| Day 0 | `FOR UPDATE SKIP LOCKED` for queue | Portable, no AQ dependency |
| Day 0 | No XA | Idempotency table provides exactly-once without XA |
| Day 0 | ArchUnit for thread-safety enforcement | Catches drift at compile time |
| Day 0 | `@ThreadSafe` marker annotation | Documents intent in code |
| Day 0 | Identity calibrators on day 1 | Real calibration needs golden set; defer |

# CLAUDE.md

Operating manual for code generation on the TFPM Address Enrichment Service.
Every prompt in `docs/DAY_BY_DAY.md` is constrained by this document.

If anything in a prompt conflicts with CLAUDE.md, CLAUDE.md wins.
If a generated change would require relaxing an ArchUnit invariant, do not
relax the invariant — change the design.

## Required reading before any code generation

Read all of the following, in order, before starting Day 0:

1. `CLAUDE.md` — this file
2. `docs/SERVICE_LIFECYCLE.md` — phase 1 (shadow) and phase 2 (in-line operational); why this is permanent infrastructure not a migration
3. `docs/ARCHITECTURE.md` — module boundaries, plugin contract, decision log
4. `docs/DOCKER_DEV_GUIDE.md` — local dev environment. Run `make up && make wait && make migrate` before any other command.
5. `docs/DATA_SOURCES.md` — where addresses come from, how they're sampled
6. `docs/RETRY_AND_RESULT.md` — `Result<T>`, `EnrichmentError`, retry layers
7. `docs/LLM_MODEL_INTEGRATION.md` — model abstraction with stream + sync modes
8. `docs/COUNTRY_STRATEGY.md` — per-country tiers, pain points, mitigations,
   acceptance criteria. The single highest-leverage doc for hitting accuracy.
9. `docs/ACCURACY_MEASUREMENT.md` — production accuracy measurement plan:
   schema for `FIELD_ATTRIBUTIONS` / `VALIDATION_FEEDBACK` / `ACCURACY_SAMPLES`,
   sampling job, canonical reporting queries, operator workflow
10. `docs/COMPLIANCE_INTEGRATION.md` — four-axis routing decision (per-field
    confidence, overall confidence, country risk tiers, pattern triggers);
    `ComplianceRouter` contract, `ComplianceDecision` outcomes, fail-safe policy
11. `docs/DAY_BY_DAY.md` — the 15-day execution plan as prompts

The contracts those docs describe are non-negotiable; this `CLAUDE.md`
codifies the rules that follow from them.

---

## Mission

Convert unstructured postal addresses (in legacy Oracle stores and inbound
payment messages) to ISO 20022 structured/hybrid format, with confidence
scores and per-field provenance, ahead of the SWIFT SR2026 deadline of
**14 November 2026**.

## Hard constraints

These are non-negotiable. Every change must satisfy all of them.

1. **Shadow-mode invariant.** Never publish to any topic matching `payments\..*`.
   Never write to the legacy Oracle schema. Never modify production payment
   data. Outputs go to `TFPM_ADDR_ENRICH` schema only.
2. **Oracle-only persistence.** No Mongo, no Redis, no Cassandra, no
   ElasticSearch, no embedded H2 in production paths.
3. **Multi-container thread safety.** Service runs as N replicas
   (typically 3-4). Every singleton bean must be thread-safe; ArchUnit
   verifies this.
4. **Multi-channel input.** Three inbound channels — HTTP (sync), Kafka
   (async cooperative-sticky), IBM MQ (transactional JMS). All three
   normalize to the same `AddressEnrichmentService.enrich(EnrichmentRequest)`.
5. **Plugin extensibility.** Adding a new structurer (vendor product,
   future SWIFT model variant, in-house model) requires zero changes to
   any existing module. New module + new bean + config flag = done.
6. **No introducing new infrastructure.** Reuse existing JPMC TFPM stack:
   Oracle, Kafka, IBM MQ, internal LLM gateway, existing Helm chart
   templates, existing observability stack.

## Stack (pinned in parent pom.xml)

- Java 21, Spring Boot 3.3.x, Maven multi-module
- Prowide ISO 20022 (`com.prowidesoftware:pw-iso20022`) — SRU2025-10.x
  (subscribe-customer SRU2026 if available)
- MapStruct 1.6.x for type-safe mappers
- jOOQ 3.19.x with `ojdbc11` for Oracle
- Liquibase for schema migrations
- Spring Kafka with cooperative-sticky assignor
- Spring JMS + IBM MQ JMS classes (`com.ibm.mq:com.ibm.mq.allclient`)
- Resilience4j 2.2.x (circuit breaker + bulkhead + retry)
- gRPC for sidecar communication (`io.grpc:grpc-netty-shaded`)
- WebClient for the LLM gateway (HTTP)
- Micrometer + OpenTelemetry, Logback JSON
- JUnit 5, AssertJ, Testcontainers (`gvenzl/oracle-free`, Kafka, IBM MQ),
  WireMock, jqwik for property-based tests, ArchUnit

## Architectural rules (enforced by ArchUnit)

These are CI gates. PR fails if violated.

```
domain MUST NOT depend on:
  - any adapter module
  - any inbound module
  - any app module
  - Spring framework
  - any third-party library except SLF4J api

app MUST NOT reference any concrete structurer or adapter class by name.
  All collaboration goes through domain interfaces.

inbound-* modules MUST NOT depend on each other.
inbound-* modules MUST NOT depend on adapter-* modules.
inbound-* modules depend on domain + app only.

adapter-oracle-legacy MUST be read-only.
  No INSERT, UPDATE, DELETE, MERGE statements anywhere in this module.

adapter-oracle-app MUST NOT reference legacy schema tables.
  Generated jOOQ classes are limited to the TFPM_ADDR_ENRICH schema.

No class outside adapter-oracle-* may reference jOOQ-generated types.

No class outside adapter-prowide may reference Prowide types.
  PstlAdr mapping is hidden behind a domain interface.

No KafkaTemplate or KafkaProducer may be configured for a topic
  matching the regex: ^payments\..*$

Any class implementing AddressStructurer, ConfidenceCalibrator, FieldMerger,
  CascadeOrchestrator, AddressEnrichmentService, or any class annotated
  @ThreadSafe MUST satisfy:
    - All instance fields are final
    - All instance fields are either:
        * primitive
        * an immutable type (record, String, enum, BigDecimal,
          java.time.* types, immutable collections)
        * a thread-safe collection (ConcurrentHashMap, etc.)
        * an atomic primitive (AtomicX, LongAdder)
        * another @ThreadSafe bean
    - No static mutable state
    - No use of ThreadLocal for application data
    - No synchronized blocks

Any class implementing AddressStructurer MUST be annotated @Calibrated.
  Confidence scores must flow through ConfidenceCalibrator, never raw to
  the FieldMerger.
```

## Forbidden in any module

- `HashMap`, `ArrayList`, `HashSet` as instance fields on `@ThreadSafe` beans
- `synchronized` blocks (use concurrent collections or atomics instead)
- `Thread.sleep` outside test code
- Direct JDBC; always go through jOOQ `DSLContext`
- Any reference to MongoDB, Redis, ElasticSearch, or any non-Oracle store
- Any new `ObjectMapper` or `JAXBContext` instance outside startup
- Lombok `@Data` on JPA/jOOQ-mapped types or any record-style POJO
- Field-injection (`@Autowired` on fields); use constructor injection only
- Service-locator patterns; no `ApplicationContext.getBean(...)` in app code

## Persistence rules

- Schema `TFPM_ADDR_ENRICH` owns all writes. Liquibase migrations are the
  only DDL source of truth.
- Two HikariCP pools, two Oracle users:
    - `TFPM_LEGACY_RO` — SELECT only on legacy address columns
    - `TFPM_ADDR_ENRICH_APP` — DML on `TFPM_ADDR_ENRICH.*`, no DDL
- **Idempotency: INSERT-first-catch-ORA-00001 pattern.** Never SELECT
  before INSERT to check existence — race condition.
- **Exception claim pattern: `SELECT ... FOR UPDATE SKIP LOCKED`.**
  Multiple makers can claim work concurrently without blocking.
- All multi-row tables: `PARTITION BY RANGE` on a date column,
  `INTERVAL` for auto-creation. Indexes must be `LOCAL` (partition-aligned).
- All large fields: `CLOB` with `IS JSON` constraint. Use Oracle 21c+
  JSON datatype if the target Oracle supports it.
- Optimistic locking via `VERSION NUMBER(10)` column on any row a human
  can edit (e.g. `EXCEPTION_QUEUE`).

## Backpressure and threading

- HTTP: Tomcat with bounded request queue, return `503 Retry-After` on
  overflow. Never block the worker thread on a downstream cascade call;
  use the cascade synchronously inside the worker (it has its own
  bulkhead) but bound the entire pipeline to ≤ 500ms total.
- Kafka: `max.poll.records=100`, `max.poll.interval.ms=300000`,
  manual ack only after Oracle commit. Cooperative-sticky assignor.
- IBM MQ: prefetch=50, transaction batch=25. **No XA** — single-resource
  Oracle commit + JMS ack with the idempotency table providing
  exactly-once semantics.
- Cascade structurer calls: Resilience4j bulkhead per structurer,
  max concurrent calls configured per-channel via properties.
- gRPC sidecar calls: 500ms timeout, circuit breaker named per sidecar.

## Per-bean thread-safety contract

All beans implementing the following MUST be `@ThreadSafe`:

- `AddressStructurer`
- `ConfidenceCalibrator`
- `FieldMerger`
- `CascadeOrchestrator`
- `AddressEnrichmentService`
- `LegacyAddressReader`
- All `*Mapper` (MapStruct generates safe ones, but mark for clarity)
- All `@RestController`, `@KafkaListener`, `@JmsListener`-bearing classes

Where shared state is genuinely needed (caches, counters, calibration
tables loaded from Oracle), the only acceptable types are:

- `ConcurrentHashMap`, `ConcurrentSkipListMap`
- `CopyOnWriteArrayList` (read-mostly only)
- `AtomicReference`, `AtomicLong`, `AtomicInteger`, `AtomicBoolean`
- `LongAdder`, `LongAccumulator` for high-frequency counters
- `StampedLock` (read-mostly with rare write)
- Caffeine cache (preferred for size-bounded caches)

## Build configuration (parent pom.xml)

```xml
<properties>
    <java.version>21</java.version>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>

    <spring-boot.version>3.3.5</spring-boot.version>
    <prowide-iso20022.version>SRU2025-10.3.5</prowide-iso20022.version>
    <mapstruct.version>1.6.3</mapstruct.version>
    <lombok.version>1.18.36</lombok.version>
    <lombok-mapstruct-binding.version>0.2.0</lombok-mapstruct-binding.version>
    <jooq.version>3.19.15</jooq.version>
    <ojdbc.version>23.5.0.24.07</ojdbc.version>
    <resilience4j.version>2.2.0</resilience4j.version>
    <grpc.version>1.68.1</grpc.version>
    <protobuf.version>3.25.5</protobuf.version>
    <ibm-mq.version>9.4.1.0</ibm-mq.version>
    <liquibase.version>4.30.0</liquibase.version>
    <archunit.version>1.3.0</archunit.version>
    <jqwik.version>1.9.1</jqwik.version>
    <testcontainers.version>1.20.4</testcontainers.version>
    <wiremock.version>3.9.2</wiremock.version>
    <caffeine.version>3.1.8</caffeine.version>
</properties>
```

### Annotation processor config (CRITICAL: order matters)

```xml
<annotationProcessorPaths>
    <!-- Lombok must be FIRST -->
    <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>${lombok.version}</version>
    </path>
    <!-- Binding tells MapStruct to wait for Lombok -->
    <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok-mapstruct-binding</artifactId>
        <version>${lombok-mapstruct-binding.version}</version>
    </path>
    <!-- MapStruct LAST -->
    <path>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct-processor</artifactId>
        <version>${mapstruct.version}</version>
    </path>
</annotationProcessorPaths>
<compilerArgs>
    <arg>-Amapstruct.defaultComponentModel=spring</arg>
    <arg>-Amapstruct.unmappedTargetPolicy=ERROR</arg>
    <arg>-Amapstruct.suppressGeneratorTimestamp=true</arg>
</compilerArgs>
```

## Testing requirements

Each module:
- Unit tests with AssertJ
- ArchUnit tests for any new architectural concept
- Coverage ≥ 80% on the `domain` module, ≥ 70% on adapters and app

Each `AddressStructurer` implementation:
- Concurrency test: 100 threads × 1000 calls, no shared-state corruption,
  results match single-threaded baseline
- Resilience test: sidecar timeout, sidecar 5xx, sidecar returns
  unsupported field (must be filtered)
- Calibration test: raw confidence flows through calibrator before merger

End-to-end (`integration-tests` module, `verify -Pit`):
- Real Oracle Testcontainer with full Liquibase migration
- Real Kafka Testcontainer with real producer/consumer
- Real IBM MQ Testcontainer with JMS listener
- Stub gRPC sidecars with deterministic responses
- The critical multi-container test (3 replicas, same message via 3 channels,
  exactly 1 row in Oracle) — see `MultiContainerIdempotencyTest`

## Definition of done (per PR)

- All tests green, including ArchUnit
- Coverage gates met
- No new ObjectMapper / JAXBContext construction (CI grep gate)
- All new beans annotated `@ThreadSafe` where applicable
- All new structurers annotated `@Calibrated`
- Resilience4j config externalized in `application.yml`, never hardcoded
- Structured log at every pipeline stage with `traceId`
- Liquibase migration added if any schema change
- Updated golden-set fixtures if any country handling changed

## When generating code, NEVER

- Add a new structurer by modifying `CascadeOrchestrator` or `FieldMerger`
- Bypass `ConfidenceCalibrator` and pass raw confidences to the merger
- Hardcode `"libpostal"`, `"swift-crf"`, or `"llm"` outside the respective
  adapter module
- Introduce a new gRPC service definition; reuse `proto/structurer.proto`
- Suggest Redis, Hazelcast, ElasticSearch, or any other infra not in the
  approved stack
- Add Lombok `@Data` to records or jOOQ-mapped types
- Use field injection (`@Autowired` on fields)
- Add `synchronized` blocks
- Catch `Throwable` or bare `Exception` without re-throwing or logging the
  full stack trace
- Add `@SuppressWarnings("unchecked")` without a comment explaining why

## When generating code, ALWAYS

- Constructor inject all dependencies; mark them `final`
- Annotate beans with `@ThreadSafe` per the rules above
- Externalize config to `application.yml` with sensible defaults
- Add a Micrometer counter or histogram for any new pipeline stage
- Propagate `traceId` through every layer (gRPC, HTTP, Kafka, JMS)
- Write the test before or alongside the implementation
- When in doubt about thread safety, make the class stateless
- **Return `Result<T>` from any operation that can fail in ways the caller
  might want to react to** (network, DB, validation, low confidence, etc.)
  Never throw on expected failure modes. See `docs/RETRY_AND_RESULT.md`.
- **Check `EnrichmentError.isRetryable()` at every retry boundary.** Retry
  policy is data, not code. The five retry layers (per-RPC, per-structurer,
  persistence, service, channel-native) are documented in
  `docs/RETRY_AND_RESULT.md` — don't invent a sixth.
- **Depend on `LlmModelClient` for any LLM call.** Never hardcode an HTTP
  client or vendor SDK in a structurer or service. The two reference
  implementations (`JpmcInternalGatewayLlmClient`,
  `OpenAiCompatibleLlmClient`) live in `adapter-llm/` — clone their
  structure for any new provider.
- **Document the data source.** When adding a new inbound channel adapter
  or a new field-extraction pattern, update `docs/DATA_SOURCES.md` so the
  channel's contract stays explicit.

---

## Cross-cutting patterns (the "new" requirements)

These three patterns are non-negotiable and apply at every layer.

### Pattern 1: `Result<T>` everywhere fallible

Every domain interface method that can fail returns `Result<T>` not `T` and
not `T-or-throw`. The `Result.Success` / `Result.Failure` sealed pair lets
callers handle both outcomes via pattern matching without `try/catch`
ceremony, and lets retry layers inspect `error.isRetryable()` to decide
whether to reattempt.

```java
// In the cascade orchestrator:
return switch (structurer.structure(raw)) {
    case Result.Success<StructuringResult>(var result) -> {
        results.add(result);
        yield Result.success(merger.merge(results));
    }
    case Result.Failure<StructuringResult>(var error) when error.isRetryable() -> {
        // Resilience4j retry already attempted Layer 1 + Layer 2.
        // Move on to next structurer; don't fail the whole cascade.
        log.warn("Retryable failure on {}: {}", structurer.name(), error);
        yield runRest(rest, raw, results);
    }
    case Result.Failure<StructuringResult>(var error) -> {
        // Non-retryable: also move on, but record diagnostically.
        log.error("Non-retryable on {}: {}", structurer.name(), error);
        yield runRest(rest, raw, results);
    }
};
```

### Pattern 2: Five retry layers, each with one job

```
Layer 5: Channel-native (Kafka rewind, JMS redeliver, HTTP client retry)
Layer 4: Service-level (idempotency-race result-load retry, max 3, ≤260ms)
Layer 3: Persistence (Spring @Retryable on Oracle deadlock, max 3)
Layer 2: Per-structurer (Resilience4j @Retry, max 2, fallback to empty result)
Layer 1: Per-RPC (gRPC service-config / WebClient.retryWhen, max 2)
```

Every layer obeys `EnrichmentError.isRetryable()`. Layer numbers are
ascending; lower-layer retries complete before upper-layer ones see the
result. Total worst-case attempts per message = 2 × 2 × 3 × 3 × ∞
(channel) but each lower layer's failures are bounded by tight timeouts,
so the realistic worst case is < 5 seconds end-to-end before channel-native
redelivery kicks in.

Configuration in `application.yml` under `resilience4j.retry.instances.*`,
`spring.retry.*`, and the channel-specific `*.listener.*` blocks.

### Pattern 3: `LlmModelClient` for any model call

```java
// In LlmAddressStructurer:
public Result<StructuringResult> structure(RawAddress raw) {
    var req = promptTemplate.render(raw, /*correlationId=*/ traceId());
    return llmClient.complete(req)                  // Result<LlmCompletionResponse>
        .flatMap(this::parseToFields)               // Result<Map<AddressField, FieldValue>>
        .map(fields -> new StructuringResult(
                llmClient.name(), fields, /*latency=*/..., /*diagnostics=*/...));
}
```

Switching between models (JPMC internal gateway, OpenAI, Ollama for dev,
a local vLLM instance) is a config change in `llm.clients.<name>`. The
structurer code does not change.

Streaming mode (`llmClient.stream(...)` returning `Flow.Publisher<LlmStreamChunk>`)
is wired but unused by the address-structurer cascade; future use cases
(maker/checker chat, RAG explanations) call it directly. The two reference
clients implement both modes.

---

If you (Claude Code) reach a point where the prompt is unclear or the
required design would violate this document, STOP and explain rather
than improvise.

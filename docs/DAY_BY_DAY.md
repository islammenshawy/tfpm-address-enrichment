# DAY_BY_DAY.md

15-day execution plan. Each day is a self-contained Claude Code session.
Run `mvn clean verify` at the end of each day; do not proceed to the next
day until the build is green.

**Pre-flight (do this before Day 0):**
- [ ] Read `CLAUDE.md` end-to-end
- [ ] Read `docs/ARCHITECTURE.md` end-to-end
- [ ] Raise the data-access ticket for representative legacy Oracle sample
- [ ] Raise the SWIFT model download ticket (parallel; not blocking)
- [ ] Confirm engineer is 100% dedicated for the duration
- [ ] Confirm IBM MQ test instance is available (or plan to run via Testcontainers)

---

## Day 0 — Scaffold and contracts (half day)

```
Read CLAUDE.md and docs/ARCHITECTURE.md.

Verify the parent pom.xml, the proto/structurer.proto, the docker-compose.yml,
and all files under domain/, archunit-tests/, infra/liquibase/changelog/
are present. Do NOT regenerate or modify them — they are pinned contracts.

Generate the per-module pom.xml files for every module listed in
docs/ARCHITECTURE.md section 2. Each module pom inherits from the parent
and declares only the dependencies it actually needs (do not copy the
full dependency list into every module).

Generate placeholder Application.java in app/ with:
- @SpringBootApplication
- @ConfigurationPropertiesScan
- main method
- One health endpoint via Spring Boot Actuator

Run: mvn clean verify

Expected outcome:
- All modules compile
- ArchUnit tests pass (they will be checking empty packages, which is fine)
- No Spring application context starts yet beyond Actuator
```

---

## Day 1 — First real structurer (libpostal)

```
Implement adapters/adapter-libpostal:

1. Generate Java gRPC stubs from proto/structurer.proto using the
   protobuf-maven-plugin (already configured in parent pom).

2. Create LibpostalAddressStructurer:
   - implements AddressStructurer
   - annotated @Component, @ThreadSafe, @Calibrated,
     @ConditionalOnProperty(name="enrichment.libpostal.enabled", havingValue="true", matchIfMissing=true)
   - constructor-injects: gRPC ManagedChannel (named "libpostal"),
     LibpostalConfidenceCalibrator
   - name() returns "libpostal"
   - supportedFields() returns immutable EnumSet of all 8 AddressFields
   - structure(RawAddress) calls the sidecar via gRPC, maps response
     to StructuringResult, applies the calibrator before returning

3. Create LibpostalConfidenceCalibrator:
   - implements ConfidenceCalibrator
   - @Component, @ThreadSafe
   - For now: identity calibrator (return raw)
   - Constructor accepts an immutable Map<AddressField, CalibrationCurve>
     loaded from application.yml; default to identity per field

4. Create LibpostalGrpcConfig:
   - @Configuration
   - @Bean ManagedChannel("libpostal") with config from
     enrichment.libpostal.endpoint
   - Resilience4j circuit breaker named "libpostal-cb"
   - Bulkhead "libpostal-bh" with max concurrent calls from properties
   - 500ms timeout

5. Tests:
   - Unit: WireMock-backed gRPC stub, verify all branches
   - Concurrency: 100 threads × 1000 calls, no shared-state corruption,
     results match single-threaded baseline
   - Resilience: simulate sidecar timeout, 5xx, returning unsupported field
     (must be filtered by structurer before reaching merger)
   - Calibration: assert raw confidence flows through calibrator

Run: mvn -pl adapters/adapter-libpostal clean verify

Expected outcome:
- Tests pass including concurrency suite
- Bean is wired but inactive without the sidecar (use @ConditionalOnProperty
  to keep it out of contexts that don't want it)
```

---

## Day 2 — LLM gateway structurer + cascade

```
Part A: Implement adapters/adapter-llm

1. LlmAddressStructurer:
   - implements AddressStructurer
   - @Component, @ThreadSafe, @Calibrated,
     @ConditionalOnProperty(name="enrichment.llm.enabled", havingValue="true")
   - constructor-injects: WebClient("llmGateway"), LlmConfidenceCalibrator,
     PromptTemplate (immutable), ObjectMapper (singleton)
   - structure(RawAddress) builds a few-shot prompt with 10 country
     examples, sends to gateway, parses JSON response, maps to
     StructuringResult
   - Bound LLM responses: only fields listed in
     enrichment.llm.fields-allowed are accepted; others filtered out
   - JSON parse failures return empty StructuringResult with diagnostics,
     never throw

2. LlmGatewayConfig:
   - @Bean WebClient("llmGateway") with bounded connection pool (max 50),
     timeout from properties
   - Resilience4j circuit breaker "llm-cb"

3. PromptTemplate:
   - immutable record holding the system prompt and few-shot examples
   - loaded from src/main/resources/prompts/address-structuring.json at startup

4. Tests:
   - Unit: WireMock for gateway, verify happy path and JSON parse failure
   - Concurrency: 100 threads × 1000 calls
   - Bounded fields: send a response with disallowed field, assert filtered

Part B: Implement app/CascadeOrchestrator and app/FieldMerger

1. CascadeOrchestrator:
   - @Component, @ThreadSafe
   - constructor-injects: List<AddressStructurer> (Spring orders them per
     enrichment.cascade.order), FieldMerger, MeterRegistry
   - structure(RawAddress) iterates through the list, collects
     StructuringResults, applies merger.isComplete() for early exit
   - Records metrics: per-structurer latency, fallback counter

2. FieldMerger:
   - @Component, @ThreadSafe
   - constructor-injects: Map<String, ConfidenceCalibrator> (keyed by
     structurer name)
   - merge(List<StructuringResult>) returns StructuredAddress with each
     field set to the highest-CALIBRATED-confidence value across all
     structurers that returned it
   - isComplete(List<StructuringResult>, RawAddress) returns true if
     all required fields (CTRY, TWN_NM) hit
     enrichment.cascade.early-exit-threshold

3. Tests:
   - Property-based (jqwik): adding a structurer to the cascade can only
     improve or preserve per-field confidence, never reduce it
   - ArchUnit: CascadeOrchestrator's bytecode must reference only
     AddressStructurer interface, not any concrete impl

Run: mvn clean verify
```

---

## Day 3 — Prowide PstlAdr mapping

```
Implement adapters/adapter-prowide:

1. PstlAdrMapper (MapStruct):
   - @Mapper(componentModel="spring", unmappedTargetPolicy=ERROR)
   - StructuredAddress -> PostalAddress27 (or current SRU class)
   - StructuredAddress -> hybrid form (some structured + AdrLine fallback)
   - PostalAddress27 -> StructuredAddress (round-trip)

2. MxMessageEnricher:
   - @Component, @ThreadSafe
   - constructor-injects: PstlAdrMapper, AddressEnrichmentService,
     JAXBContext (singleton @Bean)
   - enrich(String mxXml) parses pacs.008, finds all PstlAdr blocks,
     enriches each via service, returns updated XML
   - Marshallers/Unmarshallers created per-call (not thread-safe to share)

3. Tests:
   - Round-trip: domain -> Prowide -> XML -> Prowide -> domain == original
   - 20 real-shape pacs.008 fixtures in src/test/resources, assert each
     gets enriched correctly
   - Hybrid mode: missing STRT_NM but present ADR_LINE goes to AdrLine
   - Concurrency: 200 concurrent marshal calls, no corruption, no shared
     Marshaller leakage

Run: mvn clean verify
```

---

## Day 4 — Oracle persistence (both schemas)

```
Part A: adapters/adapter-oracle-legacy (READ-ONLY)

1. Configure jOOQ codegen plugin in this module's pom to read from local
   Oracle (docker-compose), generate into target/generated-sources/jooq
   under com.jpmc.tfpm.address.adapter.oracle.legacy.jooq
   - Includes pattern: LEGACY_PARTY|LEGACY_ADDRESS|COUNTRY_REF
   - Input schema: TFPM_LEGACY

2. JooqLegacyAddressReader:
   - implements LegacyAddressReader (domain interface)
   - @Component, @ThreadSafe
   - constructor-injects: DSLContext("legacyRead") -- the read-only one
   - readAll() returns Stream<RawAddress>, cursor-based, no in-memory load
   - findById(String) returns Optional<RawAddress>
   - NO insert, update, delete, merge methods anywhere in this module

3. ArchUnit test: no class in this module's bytecode uses INSERT, UPDATE,
   DELETE, MERGE jOOQ operations.

Part B: adapters/adapter-oracle-app (READ-WRITE)

1. Configure jOOQ codegen for TFPM_ADDR_ENRICH schema
   - Includes: IDEMPOTENCY_KEYS|STRUCTURING_RESULTS|EXCEPTION_QUEUE|AUDIT_LOG

2. JooqIdempotencyStore:
   - implements IdempotencyStore (domain interface)
   - @Component, @ThreadSafe
   - constructor-injects: DSLContext("appWrite")
   - tryClaim(String key, EnrichmentRequest req) returns ClaimResult:
     - SUCCESS: caller proceeds with cascade
     - DUPLICATE: caller loads cached result via getCachedResult(key)
   - Uses INSERT-first-catch-DataIntegrityViolationException pattern

3. JooqResultsRepository:
   - persist(StructuredAddress, StructuringTrace) returns ResultId
   - findById(ResultId) returns Optional<PersistedResult>
   - Single transaction with idempotency update

4. JooqExceptionQueue:
   - claim(int batchSize) returns List<ExceptionRecord> using
     SELECT ... FOR UPDATE SKIP LOCKED
   - resolve(ExceptionId, Resolution, version) with optimistic lock check

5. JooqAuditLog:
   - record(AuditEvent) — append-only

6. Tests:
   - Testcontainers gvenzl/oracle-free with Liquibase migrations applied
   - Idempotency: two concurrent threads INSERT same key, exactly one wins
   - Exception claim: two concurrent claim() calls return disjoint sets
   - Audit: every successful enrichment writes one audit row

Run: mvn -pl adapters/adapter-oracle-legacy,adapters/adapter-oracle-app clean verify
```

---

## Day 5 — All three inbound channels

```
Part A: inbound/inbound-http

1. EnrichmentController:
   - @RestController, @ThreadSafe
   - constructor-injects: AddressEnrichmentService, HttpDtoMapper
   - POST /api/v1/enrich -> single address
   - POST /api/v1/enrich/batch -> up to 100 addresses
   - GET /actuator/health/* via Spring Boot defaults
   - Bounded request handling: tomcat config with max-connections=200,
     accept-count=100, return 503 on overflow

2. HttpDtoMapper (MapStruct):
   - DTOs to EnrichmentRequest and back

3. GlobalExceptionHandler:
   - Translates domain exceptions to RFC 7807 problem+json responses

4. Tests:
   - @WebMvcTest with MockMvc
   - Concurrency: 100 simultaneous requests via WebTestClient

Part B: inbound/inbound-kafka

1. EnrichmentKafkaListener:
   - @Component, @ThreadSafe
   - @KafkaListener with cooperative-sticky assignor configured at the
     factory level (not on the annotation)
   - constructor-injects: AddressEnrichmentService, KafkaMessageMapper,
     KafkaTemplate("output")
   - Manual ack only after Oracle commit (record.headers() trace propagation)
   - Error handler: log + send to DLT, never silently drop

2. KafkaConfig:
   - @Bean ConsumerFactory with cooperative-sticky, manual ack
   - @Bean KafkaTemplate("output")
   - max.poll.records=100, max.poll.interval.ms=300000
   - DLT topic naming: <input-topic>.dlt

3. Tests:
   - Testcontainers Kafka, EmbeddedKafka acceptable for unit
   - Concurrency: parallel partitions, assert no cross-partition ordering issues

Part C: inbound/inbound-mq

1. EnrichmentJmsListener:
   - @Component, @ThreadSafe
   - @JmsListener with concurrency=10-25 set in application.yml
   - constructor-injects: AddressEnrichmentService, JmsMessageMapper,
     JmsTemplate("output")
   - JMS auto-ack after successful return; exception triggers redelivery
   - Idempotency table makes this exactly-once

2. MqConfig:
   - @Bean MQConnectionFactory with IBM MQ properties
   - JmsListenerContainerFactory with concurrency limits
   - DLQ configuration

3. Tests:
   - Testcontainers IBM MQ (icr.io/ibm-messaging/mq image)
   - Send-then-receive happy path
   - Redelivery: throw on first attempt, succeed on second, assert
     idempotency table prevents double-write

Run: mvn clean verify
```

---

## Day 6 — Accuracy harness + exception queue end-to-end

```
1. Accuracy harness (in app or new module test-harness):
   - Loads golden fixtures from src/test/resources/golden/{country}/*.json
   - Required country coverage: G20 + UAE + SG + HK + ZA, 10 each minimum
   - Runs each through full cascade with real adapters (sidecars stubbed)
   - Emits HTML report to /tmp/accuracy-report.html with:
     - Per-country precision/recall on each AddressField
     - Confusion matrix for CTRY predictions
     - Histogram of overall confidence by country
   - Runnable as: mvn -P accuracy verify

2. Exception queue maker/checker integration test:
   - Spin up 2 simulated maker threads
   - Each calls JooqExceptionQueue.claim(10) in a loop
   - Assert the union of claimed sets equals total exceptions, intersection
     is empty
   - One thread resolves with stale version; assert OptimisticLockException

3. Wire EXCEPTION_QUEUE writes into AddressEnrichmentService:
   - When merger result has overall confidence < threshold, write to
     EXCEPTION_QUEUE with REASON='LOW_CONFIDENCE'
   - When required fields (CTRY, TWN_NM) are missing post-cascade,
     REASON='MISSING_REQUIRED'

Run: mvn clean verify -P accuracy
```

---

## Day 7 — Multi-container concurrency tests (THE critical day)

```
Implement integration-tests module with the most important test in the project.

1. MultiContainerIdempotencyTest (the critical correctness test):

   @Testcontainers
   @SpringBootTest
   class MultiContainerIdempotencyTest {

       @Container static OracleContainer oracle = ...;
       @Container static KafkaContainer kafka = ...;
       @Container static GenericContainer<?> mq = ...;

       // Three SEPARATE Spring contexts simulating three replicas
       static ApplicationContext replica1, replica2, replica3;

       @Test
       void same_message_via_three_channels_to_three_replicas_yields_one_row() {
           var msg = randomEnrichmentRequest();

           // Send via HTTP to replica1, Kafka to replica2, MQ to replica3,
           // all simultaneously
           var futures = List.of(
               sendViaHttp(replica1, msg),
               sendViaKafka(replica2, msg),
               sendViaMq(replica3, msg)
           );
           futures.forEach(CompletableFuture::join);

           // Eventual consistency wait (Kafka/MQ are async)
           Awaitility.await().atMost(5, SECONDS).untilAsserted(() -> {
               var rows = jdbcTemplate.queryForObject(
                   "SELECT COUNT(*) FROM TFPM_ADDR_ENRICH.STRUCTURING_RESULTS " +
                   "WHERE FIELDS_JSON LIKE ?",
                   Integer.class, "%" + msg.address().raw() + "%");
               assertThat(rows).isEqualTo(1);
           });

           // All three replicas observed success (the two losers got cached result)
           futures.forEach(f -> assertThat(f.join()).isSuccess());
       }

       @Test
       void high_concurrency_no_lost_updates() {
           // 1000 distinct messages, 100 threads, mixed channels
           // Assert: 1000 rows in Oracle, 1000 successful responses,
           // exactly 0 duplicate idempotency keys (no race-induced dups)
       }

       @Test
       void replica_crashes_mid_processing_no_data_loss() {
           // Send 100 Kafka messages, kill replica2 mid-batch
           // Restart, assert all 100 eventually processed exactly once
       }
   }

2. Additional cross-cutting tests:
   - End-to-end with all real components (no stubs except sidecars)
   - Backpressure: saturate HTTP, assert 503s with correct Retry-After
   - Backpressure: saturate Kafka consumer lag, assert no data loss

Run: mvn -pl integration-tests verify -P it

This day is non-negotiable. If MultiContainerIdempotencyTest fails or
isn't written, the project is not ready for any environment beyond local dev.
```

---

## Days 8–9 — Real Oracle data, tuning

```
By now, the data-access ticket should have landed. If not, you have a
problem — escalate.

Day 8:
1. Connect to the UAT Oracle instance (or a sample dump in local Oracle)
2. Run LegacyBackfillJob via CLI on 10K rows
3. Look at the accuracy harness output
4. Identify the worst-performing countries
5. Add country-specific normalization in libpostal pre-processing for
   the top 3 worst (typical offenders: UAE area names, German Straße
   abbreviations, UK postcode validation)

Day 9:
1. Re-run on 100K rows
2. Tune the LibpostalConfidenceCalibrator per country — load real
   calibration tables from a CSV at startup
3. Tune cascade thresholds in application.yml
4. Re-run accuracy harness, confirm per-country improvements
5. Commit the calibration tables and updated thresholds

If accuracy is below 80% on any G20 country, do not proceed to UAT
deploy. Escalate or extend tuning by one more day.
```

---

## Day 10 — UAT deploy

```
1. Generate Helm values.yaml using the JPMC-standard chart template
   (do NOT generate the chart itself; reuse the platform chart)
2. Externalize all environment-specific config via ConfigMap and Secret
   references — never bake credentials into image
3. Set replica count to 3 in UAT
4. Deploy via the standard JPMC pipeline
5. Smoke run: send 1000 HTTP requests, 1000 Kafka messages, 1000 MQ
   messages to the UAT replicas; assert exactly 3000 distinct rows in
   Oracle (or fewer if there are deliberate duplicates in test data)
6. Write the runbook covering:
   - How to scale (kubectl scale deployment)
   - How to drain a replica safely
   - How to rollback to previous version
   - What each Grafana metric means
   - Escalation contact tree

Demo-ready by end of day.
```

---

## Days 11–15 — Week 3 hardening

```
Day 11: Real-world tuning against UAT traffic.
- Review 24-hour metrics: cascade fallback rates, per-country accuracy
- Iterate on calibrators if any pattern emerges

Day 12: Sanctions integration.
- Read-only call to existing sanctions screening API on enriched output
- Compare screening result before vs. after structuring (proves shadow-mode
  safety)
- Log differences but never block

Day 13: Performance and edge cases.
- Profile the cascade hot path; ensure p99 < 500ms end-to-end
- Edge cases: extremely long (>500 char) addresses, mixed-script
  (Arabic + Latin), all-caps no-punctuation, addresses with embedded
  party names

Day 14: IS&C compliance review starts.
- Submit the package: code, threat model, data flow diagram (use the
  one in ARCHITECTURE.md), test evidence, runbook
- Respond to findings as they come

Day 15: Buffer + demo prep.
- Demo to engineering leadership
- Walk through the architecture, show shadow-mode safety, show
  per-country accuracy improvements
- Confirm the SWIFT CRF download path is on track
- Plan the production cutover program (separate from this project)
```

---

## End of Day checklist (run every day)

- [ ] `mvn clean verify` passes
- [ ] All ArchUnit tests pass
- [ ] No new unsafe singletons introduced
- [ ] Coverage gate met for changed modules
- [ ] Liquibase migration added if any schema change
- [ ] Updated ADR (decision log in ARCHITECTURE.md) if architectural decision made
- [ ] Pushed to feature branch with PR open for next-day pickup

## When something goes wrong

If Claude Code suggests something that violates CLAUDE.md, push back:

> "That violates the [specific rule] in CLAUDE.md. Please find a design
> that satisfies the rule, even if it's slightly more complex."

If a test is hard to pass, the design is probably wrong. Read
ARCHITECTURE.md again before relaxing the test.

If you're behind schedule by more than a day, don't compress later days.
Identify the slip, communicate, and either de-scope (defer Day 11–13
to post-month-1) or extend the deadline by the slip amount.

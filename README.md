# TFPM Address Enrichment Service

Convert unstructured postal addresses to ISO 20022 structured format for SWIFT SR2026 compliance (deadline: **14 November 2026**).

## What It Does

Takes free-text addresses like:
```
"Office 12, Tower 3, Sheikh Zayed Road, Dubai, UAE"
```
And structures them into ISO 20022 fields:
```json
{
  "CTRY": "AE",
  "TWN_NM": "Dubai",
  "STRT_NM": "Sheikh Zayed Road",
  "BLDG_NM": "Tower 3",
  "BLDG_NB": "12"
}
```

## Architecture

```
                    ┌──────────────┐
                    │   HTTP API   │ POST /api/v1/enrich
                    └──────┬───────┘
                           │
            ┌──────────────┼──────────────┐
            │              │              │
     ┌──────┴──────┐ ┌─────┴─────┐ ┌─────┴──────┐
     │    Kafka    │ │  RabbitMQ  │ │    HTTP     │
     │  Listener   │ │  Listener  │ │ Controller  │
     └──────┬──────┘ └─────┬─────┘ └─────┬──────┘
            └──────────────┼──────────────┘
                           │
                 ┌─────────┴─────────┐
                 │  Enrichment Svc   │  Idempotency → Cascade → Persist
                 └─────────┬─────────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
       ┌──────┴──────┐ ┌──┴───┐ ┌──────┴──────┐
       │  libpostal   │ │ LLM  │ │  SWIFT CRF  │
       │  (gRPC)      │ │(HTTP)│ │   (stub)    │
       └─────────────┘ └──────┘ └─────────────┘
              │            │            │
              └────────────┼────────────┘
                           │
                    ┌──────┴───────┐
                    │ Field Merger  │  Per-field highest-confidence voting
                    └──────┬───────┘
                           │
                    ┌──────┴───────┐
                    │    Oracle    │  Results + Idempotency + Audit
                    └──────────────┘
```

### Two Services

| Service | Language | Purpose | Port |
|---------|----------|---------|------|
| **Address Enrichment App** | Java 21 / Spring Boot | Core pipeline: ingest → cascade → merge → persist | 8080 |
| **libpostal Sidecar** | Python / gRPC | Address parsing via libpostal C library | 50051 |

### Cascade Pipeline

Addresses flow through structurers in order until confidence thresholds are met:

1. **libpostal** (gRPC sidecar) — fast statistical parser, good for Western addresses
2. **LLM** (HTTP to OpenAI/JPMC gateway) — handles complex/non-Western addresses
3. **SWIFT CRF** (stub) — future SWIFT-provided model

Each structurer returns fields with raw confidence scores. The **FieldMerger** picks the highest-calibrated-confidence value per field across all structurers.

### Module Structure

```
tfpm-address-enrichment/
├── domain/                    25 types — sealed interfaces, records, zero deps
├── app/                       Composition root — ServiceConfig, cascade, compliance
├── adapters/
│   ├── adapter-libpostal/     gRPC client to libpostal sidecar
│   ├── adapter-llm/           LLM client (OpenAI + JPMC gateway)
│   ├── adapter-swift-crf/     Stub (waiting for SWIFT model)
│   ├── adapter-prowide/       ISO 20022 MX message mapper
│   ├── adapter-oracle-app/    Oracle persistence (8 classes)
│   └── adapter-oracle-legacy/ Legacy Oracle reader (backfill)
├── inbound/
│   ├── inbound-http/          REST API (4 endpoints)
│   ├── inbound-kafka/         Kafka consumer + DLT
│   └── inbound-rabbitmq/      RabbitMQ consumer + DLQ
├── archunit-tests/            17 architectural rules
├── integration-tests/         Golden set + multi-container tests
├── infra/
│   ├── docker/                docker-compose + sidecars
│   │   └── sidecars/libpostal/  Python gRPC sidecar
│   ├── liquibase/             11 migration changesets, 8 tables
│   └── reports/sql/           7 reporting queries
├── proto/                     gRPC contract (structurer.proto)
├── scripts/                   Dataset conversion tools
├── Dockerfile                 Java app container
└── Makefile                   Build/run/test commands
```

## Quick Start

### Option 1: Local dev with libpostal (recommended)

```bash
make up-sidecars     # Start libpostal sidecar (first build downloads ~2GB model)
make app             # Start app with H2 in-memory DB, calls libpostal on :50051
curl http://localhost:8080/api/v1/enrich \
  -H "Content-Type: application/json" \
  -d '{"rawAddress":"123 Main St, New York, NY 10001","countryHint":"US"}'
```

The local profile enables libpostal by default. The stub structurer is **disabled** —
only real structurers (libpostal, LLM) are used in the cascade.

Without the sidecar running, enrichment requests will return `UNSTRUCTURABLE` (no
fake results). To add LLM as a second structurer:
```bash
LLM_ENABLED=true LLM_API_KEY=sk-... make app
```

### Option 2: Full Docker stack

```bash
make up              # Start Oracle + Kafka + RabbitMQ + WireMock
make up-sidecars     # Start libpostal sidecar
make migrate         # Apply Liquibase schema
make docker-app      # Build + start the app container
```

### Option 3: App locally, infra in Docker

```bash
make up              # Start infrastructure containers
make migrate         # Apply schema
mvn -pl app spring-boot:run   # Run app on host (connects to Docker infra)
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/enrich` | Enrich a single address |
| POST | `/api/v1/enrich/batch` | Enrich up to 100 addresses |
| GET | `/api/v1/results/{id}` | Retrieve a persisted result |
| POST | `/api/v1/replay/{id}` | Replay an exception queue item |
| GET | `/actuator/health` | Health check |
| GET | `/actuator/prometheus` | Metrics |

### Example Request

```bash
curl http://localhost:8080/api/v1/enrich \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: test-001" \
  -d '{
    "rawAddress": "383 Madison Avenue, Suite 4500, New York, NY 10179",
    "countryHint": "US",
    "locale": "en-US"
  }'
```

### Example Response

```json
{
  "correlationId": "test-001",
  "outcome": "SUCCESS",
  "fields": {
    "CTRY": {"value": "US", "confidence": 0.95},
    "TWN_NM": {"value": "New York", "confidence": 0.90},
    "CTRY_SUB_DVSN": {"value": "NY", "confidence": 0.88},
    "STRT_NM": {"value": "Madison Avenue", "confidence": 0.92},
    "BLDG_NB": {"value": "383", "confidence": 0.95},
    "PST_CD": {"value": "10179", "confidence": 0.93}
  },
  "overallConfidence": 0.88,
  "resultRowId": 42,
  "processedAt": "2026-05-14T10:30:00Z"
}
```

## Inbound Channels

| Channel | Protocol | Config Toggle | DLT/DLQ |
|---------|----------|---------------|---------|
| HTTP | REST (sync) | Always on | 503 Retry-After |
| Kafka | Consumer (async) | `KAFKA_ENABLED` | DLT topic |
| RabbitMQ | AMQP (async) | `RABBITMQ_ENABLED` | DLQ via basicNack |

All three channels normalize to `AddressEnrichmentService.enrich(EnrichmentRequest)`.

## Compliance Routing

Four-axis decision engine evaluates every enrichment result:

1. **Per-field confidence floors** — CTRY ≥ 0.95, TWN_NM ≥ 0.80
2. **Overall confidence floor** — ≥ 0.85
3. **Country risk tiers** — OFAC/FATF high-risk countries (13 in CSV)
4. **Pattern triggers** — sanctions watch patterns (7 regex in CSV)

Shadow mode (`COMPLIANCE_SHADOW=true`): evaluates but doesn't route.

## Database Schema

8 Oracle tables (H2 for local dev):

| Table | Purpose | Partitioned |
|-------|---------|-------------|
| IDEMPOTENCY_KEYS | INSERT-first deduplication | Daily |
| STRUCTURING_RESULTS | Enrichment output + confidence | Daily |
| EXCEPTION_QUEUE | Low-confidence items for review | 30-day |
| AUDIT_LOG | Append-only event trail | 30-day |
| FIELD_ATTRIBUTIONS | Per-field structurer attribution | Weekly |
| COMPLIANCE_ROUTING | Compliance decision audit | Weekly |
| VALIDATION_FEEDBACK | Operator corrections | Monthly |
| ACCURACY_SAMPLES | Stratified samples for review | Monthly |

## Testing

```bash
make verify          # Unit tests + ArchUnit (17 rules)
make it              # Integration tests (golden set: 135 fixtures)
```

**135 golden set fixtures** across 8 Tier-0 countries + SWIFT MT patterns:
- AE (15), CH (15), CN (15), DE (15), GB (15), HK (15), SG (15), US (20)
- SWIFT MT (10): name overflow, 35-char truncation, mid-word breaks

**~235 test methods** across 30 test files.

## Key Design Decisions

- **No @Transactional on cascade** — LLM calls can take >500ms; holding a DB transaction open during HTTP calls wastes connections. Each persistence method is independently `@Retryable`.
- **RabbitMQ instead of IBM MQ** — simpler setup, same semantics, configurable on/off.
- **Cascade timeout (500ms)** — if early structurers consume the budget, later ones are skipped.
- **SourceChannel.RABBITMQ** — not MQ. Intentional design decision.
- **Result<T> at cascade level, not service level** — the service absorbs failures and always returns a meaningful `EnrichmentResult`.

## Build Commands

| Command | What |
|---------|------|
| `make build` | Build Java app (`mvn clean install -DskipTests`) |
| `make build-all` | Build Java app + libpostal sidecar Docker image |
| `make verify` | Run all unit tests + ArchUnit rules |
| `make it` | Run integration tests (golden set) |
| `make app` | Run app locally in-memory (H2, no Docker) |
| `make docker-all` | Build + start everything in Docker |
| `make up` | Start infrastructure (Oracle, Kafka, RabbitMQ, WireMock) |
| `make up-sidecars` | Start libpostal sidecar |
| `make down` | Stop containers |
| `make reset` | Stop containers + delete volumes |

## Stack

- Java 21, Spring Boot 3.3.5, Maven
- jOOQ 3.19.x + Oracle / H2
- Liquibase for migrations
- Spring Kafka + RabbitMQ (configurable)
- Resilience4j (circuit breaker + bulkhead)
- gRPC for sidecar communication
- WebClient for LLM gateway
- Micrometer + Prometheus
- Logback JSON (logstash-logback-encoder)
- Python 3.12 + libpostal for sidecar
- Testcontainers for integration tests

## Documentation

| File | Contents |
|------|----------|
| `CLAUDE.md` | Operating manual — constraints, rules, patterns |
| `docs/ARCHITECTURE.md` | Module boundaries, decision log |
| `docs/COMPLIANCE_INTEGRATION.md` | Four-axis routing, fail-safe policy |
| `docs/COUNTRY_STRATEGY.md` | Per-country tiers and mitigations |
| `docs/ACCURACY_MEASUREMENT.md` | Production accuracy plan |
| `docs/DATA_SOURCES.md` | Address sources, extraction patterns |
| `docs/LLM_MODEL_INTEGRATION.md` | Model abstraction, stream + sync |
| `docs/RETRY_AND_RESULT.md` | Result<T>, five retry layers |
| `docs/DAY_BY_DAY.md` | 15-day execution plan |
| `docs/SERVICE_LIFECYCLE.md` | Phase 1 (shadow) → Phase 2 (in-line) |

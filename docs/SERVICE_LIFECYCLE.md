# SERVICE_LIFECYCLE.md

The TFPM Address Enrichment Service is **permanent operational infrastructure**,
not a migration project. This document exists because that question gets asked
in every IS&C review and every annual planning cycle, and the answer needs to
be unambiguous and well-reasoned.

---

## The two phases, both operational

### Phase 1: Shadow mode (now → ~Q1 2027)

- Service runs alongside the existing payment path; never modifies legacy
  Oracle, never publishes to `payments.*` topics
- Processes the legacy backfill (~50–200M TPS rows) once
- Processes a continuous tee from the live Kafka payment events stream
- Writes structured outputs to `TFPM_ADDR_ENRICH` schema only
- Validation against the accuracy harness; per-country tuning to hit
  the tier acceptance bars in `COUNTRY_STRATEGY.md`
- Compliance routing is OPTIONAL during this phase but the mechanism
  is wired in (see `COMPLIANCE_INTEGRATION.md`)

Phase 1 ends when:
- Backfill is complete
- All Tier 0 countries hit accuracy targets
- IS&C signs off on production cutover
- The downstream MX message construction is ready to consume the
  structured output

### Phase 2: In-line operational service (Q1 2027 onward)

- Service is in the live payment path. Inbound payments flow through
  it before sanctions screening, before MX construction
- Cascade output is authoritative — it's what populates the SWIFT
  message's `PstlAdr` block
- Compliance routing is MANDATORY: low-confidence and high-risk
  outputs route to the existing compliance flow before the payment proceeds
- Idempotency, multi-replica concurrency, sub-500ms SLO continue
  to apply (the service was designed for these from day one)
- Continuous accuracy measurement via the sampling-and-review
  process in `ACCURACY_MEASUREMENT.md`
- Continuous calibrator and prompt tuning as new countries / source
  systems / counterparty patterns appear

Phase 2 has no end date.

---

## Why this isn't a migration-only project

If you're tempted to scope this as "build it, run it for a year, then
decommission," the following sources of unstructured addresses will
prove you wrong:

| Source | Why it produces unstructured addresses indefinitely |
|---|---|
| MT messages from correspondent banks | MT 700/400/420/760 series remain in use for trade finance regardless of SR2026 — many regional banks will not migrate to MX |
| Corporate file ingestion (MQ) | Corporates submit in whatever format their ERP exports; structured-address adoption among non-bank entities lags by years |
| Documentary trade | B/L, invoices, packing lists are PDF/image; OCR-extracted addresses are unstructured by definition |
| Counterparty onboarding | New corporate counterparties, JV entities, acquired entities arrive with whatever data they have |
| Operator manual entry | Operations and ops support enter addresses every day; no UI prevents free-text entry |
| Less-modernised counterparties | Many regional banks and corporates will be on legacy formats for the foreseeable future |
| Acquisitions and JVs | Every M&A event imports unstructured data from the acquired entity's systems |

Even after SR2026 fully bites and TPS-native data is structured,
all of the above keep producing unstructured addresses that need
the cascade.

---

## Implications for ownership and budget

Because this is permanent operational infrastructure, the engineering
ownership model needs to be operational, not project:

- **Ongoing engineering team** with on-call rotation, not a project
  team that disbands
- **Annual budget** for infrastructure (Oracle, Kafka, MQ, sidecar
  containers, LLM gateway tokens), not one-time
- **Continuous calibration cycle** — quarterly accuracy review per
  country, calibration retuning as needed
- **Vendor relationship** for any country-specific structurer
  (Loqate UAE, Baidu CN if/when adopted)
- **SWIFT model lifecycle** — the SWIFT CRF model will receive updates;
  someone needs to track and integrate them
- **LLM model lifecycle** — provider changes (gpt-4o → gpt-5,
  internal gateway upgrades) require validation against the accuracy
  harness before promotion

A reasonable steady-state team size is 2-3 engineers + 1 ops analyst
sharing accuracy review duties.

---

## What changes between Phase 1 and Phase 2

| Concern | Phase 1 (shadow) | Phase 2 (in-line) |
|---|---|---|
| Path | parallel | in-line |
| Latency budget | best-effort | < 500ms p99 hard SLO |
| Compliance routing | optional | mandatory |
| Failure mode | log & continue | block payment if confidence too low and compliance unreachable |
| Output destination | TFPM_ADDR_ENRICH schema only | also feeds MX message construction |
| Replica count | 3 | 4-6 (sized to peak payment volume) |
| On-call | engineering best-effort | full L1/L2 operational support |

The code surface is the same in both phases. The configuration and
operational posture changes.

---

## What does NOT change between phases

- The cascade architecture
- The plugin contract for structurers
- The `Result<T>` and retry semantics
- The Oracle schema (modulo growth)
- The per-country strategy
- The accuracy measurement framework
- The shadow-mode invariant against the LEGACY Oracle schema —
  even in Phase 2, the legacy schema is read-only forever

The bundle was designed Phase-2-ready from day one. That's why all
the infrastructure for multi-replica concurrency, idempotency,
exactly-once processing, observability, and compliance routing
exists from Day 0 even though Phase 1 doesn't strictly need all of it.

If you find yourself in Phase 2 prep work and discover something
that "wasn't built for permanent operations," that's a bug in the
original design, not a phase boundary. Fix it; don't add a Phase 3.

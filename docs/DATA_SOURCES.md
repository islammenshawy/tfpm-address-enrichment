# DATA_SOURCES.md

Where addresses enter this service from, what they look like, how they're
extracted, and the streaming-vs-batch approach for each source.

The cascade itself is source-agnostic — every channel adapter normalises to
the same `EnrichmentRequest`. This document is the authoritative reference
for what those adapters need to produce and what context is available
upstream.

---

## 1. Source inventory

Four sources, three runtime channels, one batch backfill path.

| Source | Channel | Mode | Volume (peak) | Latency budget |
|---|---|---|---|---|
| Legacy Oracle TPS | (batch) | streaming cursor | 50–200M rows total, run once | hours, offline |
| Kafka payment events | KAFKA | streaming | 100–500 msg/sec | < 500ms p99 |
| IBM MQ corporate ingestion | MQ | streaming | 10–50 msg/sec | < 1s p99 |
| HTTP REST (replay, ad-hoc, maker UI) | HTTP | request/response | < 10 req/sec | < 500ms p99 |

The Kafka and MQ paths are the **shadow-mode tee**: they read events that
the production payment path has already processed. The legacy Oracle TPS
path is the **historical backfill**: it processes addresses that exist
today and have never been structured. The HTTP path is **operational**:
manual replay from the dead-letter topic, single-message debug from
maker/checker UI, ad-hoc lookups.

---

## 2. Source 1: Legacy Oracle TPS (batch backfill)

### What's there

The existing Trade Processing System holds party data across roughly
50–200 million rows split between:

- `TFPM_LEGACY.PARTY` — counterparty and customer master (~30M rows)
- `TFPM_LEGACY.ADDRESS` — addresses linked to parties (~80M rows)
- `TFPM_LEGACY.PAYMENT_INSTRUCTION` — historical payment metadata (~100M rows)
- `TFPM_LEGACY.COUNTRY_REF` — country code reference (~250 rows)

Confirm exact table names and volumes with the data-access ticket; these
are estimates from the TFPM platform team's last public count.

### Address shape in the legacy schema

The legacy `ADDRESS` table is denormalised free-text. Typical row:

```
PARTY_ID            : 0001234567
ADDRESS_LINE_1      : "Office 1204, Tower 3"
ADDRESS_LINE_2      : "Sheikh Zayed Road"
ADDRESS_LINE_3      : "Dubai"
ADDRESS_LINE_4      : "United Arab Emirates"
COUNTRY_CODE        : "AE"        ← this is the only structured field
POSTAL_CODE         : NULL        ← almost always NULL in legacy data
LAST_UPDATED        : 2018-03-14
SOURCE_SYSTEM       : "TPS-MIG-2014"
```

The `LegacyAddressReader.LegacyAddressRow.raw()` field is constructed by
the adapter as:

```java
String.join(", ",
    Stream.of(line1, line2, line3, line4)
          .filter(StringUtils::isNotBlank)
          .toList())
```

The `countryHint` is populated from `COUNTRY_CODE` if present and
non-empty, otherwise empty string.

### Read pattern

Cursor-based stream over a partitioned query, never a single SELECT
that materialises millions of rows. The backfill job:

1. `SELECT MIN(PARTY_ID), MAX(PARTY_ID) FROM PARTY` to get bounds.
2. Split bounds into N partitions (default 32).
3. For each partition, open a cursor:
   ```sql
   SELECT p.PARTY_ID, a.ADDRESS_LINE_1, a.ADDRESS_LINE_2, a.ADDRESS_LINE_3,
          a.ADDRESS_LINE_4, a.COUNTRY_CODE, a.POSTAL_CODE
   FROM   PARTY p
   JOIN   ADDRESS a ON a.PARTY_ID = p.PARTY_ID
   WHERE  p.PARTY_ID BETWEEN :lo AND :hi
   AND    a.IS_PRIMARY = 'Y'
   ORDER  BY p.PARTY_ID
   ```
4. Stream rows through the cascade. Each row goes through the same
   `AddressEnrichmentService.enrich(...)` path the runtime channels use
   — same idempotency, same persistence, same exception queue handling.

The backfill is naturally idempotent (the `IDEMPOTENCY_KEYS` table
dedupes), so it can be restarted from any point or run in parallel with
the runtime channels without data corruption.

Throughput target: 1000 rows/sec/partition, so 32 partitions × 1000 =
32K addresses/sec. At that rate, 100M rows backfills in ~50 minutes
of wall-clock time.

### Sampling for the accuracy harness

A representative sample for golden-set evaluation is drawn by:

```sql
-- 100 random rows per country, restricted to active parties
SELECT * FROM (
    SELECT a.*, ROW_NUMBER() OVER (PARTITION BY a.COUNTRY_CODE
                                   ORDER BY DBMS_RANDOM.VALUE) AS rn
    FROM ADDRESS a
    JOIN PARTY p ON p.PARTY_ID = a.PARTY_ID
    WHERE p.STATUS = 'ACTIVE'
)
WHERE rn <= 100;
```

Required country coverage for accuracy harness: G20 + UAE + SG + HK + ZA,
minimum 10 hand-validated samples per country.

---

## 3. Source 2: Kafka payment events (real-time)

### What's there

Topic `payments.events.in` in the prod Kafka cluster. Read-only consumer.
Messages are CloudEvents-wrapped pacs.008.001 XML or JSON.

### Approximate envelope

```json
{
  "specversion": "1.0",
  "type": "com.jpmc.tfpm.payments.created.v1",
  "source": "/tps/payments",
  "id": "pmt-2026-1112-78a3f4",
  "time": "2026-05-09T14:23:11Z",
  "datacontenttype": "application/xml",
  "data": "<Document xmlns='urn:iso:std:iso:20022:tech:xsd:pacs.008.001.10'>...</Document>"
}
```

### Address extraction

Parse the pacs.008. Addresses live in multiple `PstlAdr` blocks:

```
Document/FIToFICstmrCdtTrf/CdtTrfTxInf/Dbtr/PstlAdr     (debtor)
Document/FIToFICstmrCdtTrf/CdtTrfTxInf/Cdtr/PstlAdr     (creditor)
Document/FIToFICstmrCdtTrf/CdtTrfTxInf/UltmtDbtr/PstlAdr (ultimate debtor)
Document/FIToFICstmrCdtTrf/CdtTrfTxInf/UltmtCdtr/PstlAdr (ultimate creditor)
```

For each `PstlAdr` block:

1. If the block already has structured fields populated (`StrtNm`,
   `BldgNb`, `PstCd`, `TwnNm`, `Ctry`), **skip** — already SR2026-compliant.
2. If the block has only `AdrLine` elements, extract those, join with
   ", ", and route to enrichment with `countryHint` from `Ctry` if present.
3. The `correlationId` is the CloudEvent `id` plus the party role
   (`-debtor`, `-creditor`, etc.) so the same payment with multiple
   addresses produces distinct rows.

### Kafka-specific config

- Consumer group: `tfpm-address-enrichment`
- Assignor: `CooperativeStickyAssignor` (smooth rebalancing, no stop-the-world)
- `enable.auto.commit: false` — manual ack only after Oracle commit
- `max.poll.records: 100`
- `max.poll.interval.ms: 300000`
- `isolation.level: read_committed` (we only see committed payment events)
- DLT topic: `payments.events.in.address-enrichment.dlt`

### Streaming via the structurer cascade

For the Kafka channel, "streaming" means:

- Records arrive in micro-batches (up to 100 per poll).
- Each record processed sequentially within a partition (preserving order).
- The cascade runs synchronously per record (the LLM stage may stream
  internally — see `LLM_MODEL_INTEGRATION.md`).
- Manual ack only after the entire batch's Oracle commit succeeds.

This gives at-least-once delivery from Kafka + idempotency table
deduplication = exactly-once processing guarantee.

---

## 4. Source 3: IBM MQ corporate ingestion (legacy MT)

### What's there

Queue `TFPM.PAYMENT.IN` on `QM1`. Messages are SWIFT MT format (legacy,
not MX). Typical types: MT 103 (single customer credit transfer),
MT 202 (general financial institution transfer), MT 700 (issue of LC).

### Address extraction

In MT messages, addresses live in field-tagged blocks:

```
:50K:/123456789                        ← Ordering Customer (Field 50K)
ACME TRADING LLC
OFFICE 1204, TOWER 3
SHEIKH ZAYED ROAD
DUBAI, UNITED ARAB EMIRATES

:59:/987654321                         ← Beneficiary (Field 59)
WIDGETCO PTE LTD
10 ANSON ROAD #12-34
INTERNATIONAL PLAZA
SINGAPORE 079903
```

For each address-bearing field:

1. The first line is the party name — extract separately, do NOT include in
   the address string.
2. Lines 2-N are the address (concatenate with ", ").
3. Country hint comes from heuristics on the last line plus, where
   present, the related Field 57 (account-with bank) BIC.
4. The `correlationId` is the MT message reference (Field 20) + field tag.

### MQ-specific config

- Connection: IBM MQ JMS classes (no native MQI)
- Listener concurrency: 10–25
- Acknowledge mode: `client` (commit after Oracle write)
- DLQ: `TFPM.PAYMENT.IN.DLQ` (poison messages after 3 redeliveries)
- **No XA**: idempotency table provides exactly-once

### Why no XA

JPMC's XA setup is operationally expensive — coordinator JNDI bindings,
two-phase commit overhead, recovery complexity. Idempotency-by-database
(INSERT-first-catch-ORA-00001) gives the same exactly-once guarantee at
a fraction of the operational burden.

---

## 5. Source 4: HTTP REST (replay, ad-hoc, maker UI)

### Endpoints

```
POST /api/v1/enrich
    Body: { "address": "...", "countryHint": "AE", "locale": "en-AE",
            "correlationId": "optional, server generates if absent" }
    Returns: EnrichmentResponse with structured fields, confidence, result row id

POST /api/v1/enrich/batch
    Body: { "items": [{...}, ...]  }   (up to 100 per call)
    Returns: BatchResponse with per-item result

GET /api/v1/results/{resultId}
    Returns: persisted result for a given STRUCTURING_RESULTS row

POST /api/v1/replay/{exceptionId}
    Body: { "resolution": {...} }
    Returns: replays an exception-queue item with operator-corrected fields
```

### Use cases

1. **Replay from DLT.** When a Kafka or MQ message lands in a dead-letter
   topic/queue (poison message, schema mismatch, sidecar outage during
   processing), an operator inspects it and replays via HTTP after fixing
   the underlying issue.
2. **Ad-hoc.** Engineering or operations needs to structure a single
   address to debug an issue or confirm a model change.
3. **Maker/Checker UI.** The exception queue UI uses HTTP `POST
   /api/v1/replay/{exceptionId}` to send the operator's corrected fields
   back through persistence (re-running the cascade is optional).

### HTTP-specific concerns

- Bounded request handling: Tomcat with `max-connections: 500`,
  `accept-count: 100`. Overflow returns `503 Retry-After`.
- Synchronous: client blocks until cascade completes (typical < 500ms).
- The cascade has its own bulkhead (Resilience4j); the HTTP worker
  thread is bound to the cascade duration but the cascade itself can
  refuse work if its bulkhead is saturated.

---

## 6. Common extraction approach

Regardless of source, the channel adapter does exactly four things:

```
1. Parse the channel-native message into a structured representation
   (DOM for XML, deserialised POJO for JSON, parsed MT for MQ, DTO for HTTP)
2. Extract the raw address text(s) plus available context
   (country hint from a structured field if present, otherwise empty)
3. Build EnrichmentRequest objects, one per address, with:
       - correlationId   (deterministic from source: messageId + party role)
       - sourceChannel   (HTTP / KAFKA / MQ)
       - RawAddress(raw text, countryHint, locale)
4. Pass each request to AddressEnrichmentService.enrich(...)
```

The adapter does NOT do any structuring, calibration, or persistence.
Those are service responsibilities. The adapter does NOT swallow errors:
infrastructure failures bubble up so the channel-native retry mechanism
(JMS redelivery, Kafka rewind) can take over.

---

## 7. Sampling and golden-set strategy

Different sources yield different distributions of address shapes. The
accuracy harness MUST sample representatively, not just from the easy
source.

| Source | Why sample | Sample target |
|---|---|---|
| Legacy Oracle | Older formatting, abbreviation conventions, denormalised text | 50% of golden set |
| Kafka | Real-time addresses, includes self-service-entered ones | 30% of golden set |
| MQ (legacy MT) | SWIFT-formatting conventions, line-length quirks | 20% of golden set |

For each sampled address, an analyst hand-validates the structured form
against ISO 20022 PstlAdr fields. The harness asserts the cascade
produces fields that match the hand-validated form, per country.

Pass criterion before UAT deploy: ≥ 80% per-field accuracy on G20
countries, ≥ 70% on UAE / SG / HK / ZA.

---

## 8. Data lineage and audit

Every enriched address can be traced back to its source via:

- `STRUCTURING_RESULTS.CORRELATION_ID` (deterministic from source identifiers)
- `STRUCTURING_RESULTS.SOURCE_CHANNEL` (HTTP / KAFKA / MQ)
- `AUDIT_LOG` rows for every state change with full source metadata

The `STRUCTURER_TRACE` JSON column on `STRUCTURING_RESULTS` records which
structurer contributed which field with what raw and calibrated
confidence. This is required for IS&C audit and for future calibration
retraining.

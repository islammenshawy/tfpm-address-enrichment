# COMPLIANCE_INTEGRATION.md

How the cascade decides whether an enriched address goes through to
the live payment path or routes to the existing JPMC compliance flow
for review first.

This is the bridge between the address-structuring service and the
larger CIB risk and compliance platform. The cascade itself does not
make compliance decisions — it makes ROUTING decisions: "this output
is high-risk enough that the existing compliance flow should examine it
before the payment proceeds."

---

## 1. The four-axis routing decision

A single confidence threshold is not enough. The router evaluates four
independent triggers; **any one** firing routes to compliance.

### Axis 1: Per-field confidence floors

Different fields have different consequences when wrong:

- `CTRY` wrong → entire sanctions screening defeated. Floor: 0.95.
- `TWN_NM` wrong → KYC and TBML pattern matching degraded. Floor: 0.80.
- `CTRY_SUB_DVSN` wrong → state/province sanctions (e.g. Crimea, Donetsk) missed. Floor: 0.75.
- `STRT_NM`, `BLDG_NB`, `BLDG_NM` wrong → degraded address dedup, but not catastrophic. No floor (informational only).

Below floor on any required field → ROUTE.

### Axis 2: Overall confidence floor

Aggregate trigger: even if every individual field is above its floor,
overall confidence below 0.85 indicates the cascade is uncertain about
the address as a whole. Default routing threshold: 0.85.

### Axis 3: Country risk tiers

Some countries always require compliance review regardless of confidence.
The list is config-driven in `application.yml`; the default seeds with:

- **OFAC SDN-listed jurisdictions**: IR, KP, SY, CU
- **OFAC sectoral sanctions**: RU, BY (full list), VE (sectoral)
- **Comprehensive embargoes**: as published by Treasury — list updates
  driven by an ops process, not engineering
- **High-risk per FATF grey/black list**: subject to quarterly review

Country in any of these → ROUTE regardless of confidence.

### Axis 4: Pattern triggers in raw address

Specific terms in the raw address text always route, even if the
cascade structures cleanly:

- Sanctioned entity names (Bank Markazi, Sepah, IRGC subsidiaries, etc.)
- Free zone markers in high-risk jurisdictions (some Iran free zones)
- Patterns flagged by financial crime as elevated risk (config-driven)

Pattern match → ROUTE.

The pattern list is small (typically <100 entries) but high-signal.
Maintained by financial crime, not engineering. Loaded at startup from
a CSV resource referenced by config.

---

## 2. The decision API

```java
public interface ComplianceRouter {
    /** Decide what to do with an enriched address. */
    ComplianceDecision evaluate(EnrichmentResult result, EnrichmentRequest request);
}
```

Returns a `ComplianceDecision`:

```java
public sealed interface ComplianceDecision {

    /** Proceed with the payment without compliance review. */
    record Bypass() implements ComplianceDecision {}

    /** Send to compliance for review before the payment proceeds. */
    record RouteToCompliance(
        ComplianceReason primaryReason,
        Set<ComplianceReason> allReasons,
        String urgency  // "STANDARD" | "EXPEDITED" | "BLOCKING"
    ) implements ComplianceDecision {}

    /** Hard block — never proceed regardless of compliance verdict.
     *  Used only for OFAC SDN exact matches at >0.95 confidence. */
    record Block(ComplianceReason reason, String justification)
        implements ComplianceDecision {}
}
```

`ComplianceReason` is a closed enum:

```
LOW_FIELD_CONFIDENCE     - one or more required fields below floor
LOW_OVERALL_CONFIDENCE   - aggregate below threshold
HIGH_RISK_COUNTRY        - country in restricted list
SANCTIONS_PATTERN_MATCH  - raw address matched a watch pattern
SANCTIONS_EXACT_MATCH    - structured fields matched a known sanctioned entity
MANUAL_OVERRIDE          - operator-flagged
SCHEMA_INCOMPLETE        - cascade missing SR2026 mandatory fields
UNSTRUCTURABLE           - cascade returned no usable fields
```

---

## 3. Where the decision happens in the flow

```
EnrichmentRequest
    │
    ▼
AddressEnrichmentService.enrich()
    │
    ├─ Idempotency claim
    ├─ Cascade (libpostal → swift-crf → llm)
    ├─ FieldMerger
    ├─ Persist to STRUCTURING_RESULTS
    │
    ▼
ComplianceRouter.evaluate(result, request)    ← new step
    │
    ├─ ComplianceDecision.Bypass            → publish to output channel, ack source
    ├─ ComplianceDecision.RouteToCompliance → publish to compliance channel,
    │                                          write COMPLIANCE_ROUTING row,
    │                                          ack source (compliance owns the rest)
    └─ ComplianceDecision.Block             → write COMPLIANCE_ROUTING row with BLOCK,
                                              ack source, return error to caller
```

The decision happens AFTER persistence so the routing decision and the
structured output are always consistent — there's no scenario where
compliance reviews an address and Oracle has a different version of it.

---

## 4. Configuration model

```yaml
enrichment:
  compliance:
    enabled: ${COMPLIANCE_ENABLED:true}
    client: jpmc-compliance-gateway   # which ComplianceClient bean to use

    # Per-field confidence floors. Fields not listed have no floor.
    field-confidence-floor:
      CTRY:           0.95
      TWN_NM:         0.80
      CTRY_SUB_DVSN:  0.75

    # Aggregate overall-confidence floor.
    overall-confidence-floor: 0.85

    # Countries that always route regardless of confidence.
    # Updated by ops (not engineering) from the OFAC + FATF lists.
    high-risk-countries-resource: classpath:compliance/high-risk-countries.csv

    # Pattern triggers that always route. Maintained by financial crime.
    pattern-triggers-resource: classpath:compliance/sanctions-patterns.csv

    # What to do when the compliance system is unreachable.
    # CONSERVATIVE = treat as ROUTE (back-pressure into queue)
    # PERMISSIVE   = treat as BYPASS (keep payments moving)
    # NEVER use PERMISSIVE in production for trade finance.
    fail-safe-action: CONSERVATIVE

    # Where to send the routed message
    routing:
      destination-type: ${COMPLIANCE_DEST:KAFKA}   # KAFKA | HTTP | MQ
      kafka-topic:      ${COMPLIANCE_TOPIC:compliance.address.enrichment.review}
      http-endpoint:    ${COMPLIANCE_HTTP:}
      mq-queue:         ${COMPLIANCE_MQ:}

    # Timeouts and retry on the compliance dispatch
    dispatch:
      timeout-ms: 1000
      max-retries: 2
      retry-backoff-ms: 100

    # Phase 1 (shadow) override: log decisions but don't actually route.
    # When the bundle is in shadow mode, set this true to gather data
    # about what WOULD have been routed without affecting live flow.
    shadow-mode: ${COMPLIANCE_SHADOW:false}
```

---

## 5. The persistence model

A new `COMPLIANCE_ROUTING` table captures every decision, whether the
result was bypassed or routed:

```sql
CREATE TABLE COMPLIANCE_ROUTING (
  ROUTING_ID                NUMBER(19) GENERATED ALWAYS AS IDENTITY,
  RESULT_ID                 NUMBER(19)         NOT NULL,  -- FK to STRUCTURING_RESULTS
  COUNTRY_HINT              CHAR(2),
  DECISION                  VARCHAR2(20)       NOT NULL,  -- BYPASS | ROUTE | BLOCK
  PRIMARY_REASON            VARCHAR2(40),
  ALL_REASONS_JSON          CLOB,                          -- ["LOW_FIELD_CONFIDENCE","HIGH_RISK_COUNTRY"]
  URGENCY                   VARCHAR2(16),                  -- STANDARD | EXPEDITED | BLOCKING
  ROUTED_AT                 TIMESTAMP(6),
  ROUTED_TO                 VARCHAR2(64),                  -- destination identifier
  COMPLIANCE_REQUEST_ID     VARCHAR2(64),                  -- correlation back to compliance system
  COMPLIANCE_VERDICT        VARCHAR2(20),                  -- PASS | REJECT | NEEDS_REVIEW | TIMEOUT
  COMPLIANCE_VERDICT_DETAIL CLOB,                          -- JSON from compliance response
  COMPLIANCE_RESPONDED_AT   TIMESTAMP(6),
  STATUS                    VARCHAR2(16) DEFAULT 'PENDING' NOT NULL,
                                                           -- PENDING | DISPATCHED | RESOLVED | FAILED
  CREATED_AT                TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL,
  VERSION                   NUMBER(10) DEFAULT 0 NOT NULL,
  CONSTRAINT PK_COMP_ROUTING PRIMARY KEY (ROUTING_ID, CREATED_AT),
  CONSTRAINT FK_COMP_RESULT  FOREIGN KEY (RESULT_ID) REFERENCES STRUCTURING_RESULTS(RESULT_ID),
  CONSTRAINT CK_COMP_DECISION CHECK (DECISION IN ('BYPASS','ROUTE','BLOCK')),
  CONSTRAINT CK_COMP_STATUS   CHECK (STATUS IN ('PENDING','DISPATCHED','RESOLVED','FAILED'))
)
PARTITION BY RANGE (CREATED_AT)
INTERVAL (NUMTODSINTERVAL(7, 'DAY'));
```

The `BYPASS` rows matter as much as the `ROUTE` rows — they're the
audit trail that proves we considered compliance for every payment,
not just the ones we routed.

---

## 6. The dispatch — how the routing actually happens

After the router returns `RouteToCompliance`, the `ComplianceDispatcher`
sends a structured message to whichever channel is configured. The
message format is stable across channels:

```json
{
  "version": "1.0",
  "result_id": 84592017,
  "correlation_id": "pmt-2026-1112-78a3f4-debtor",
  "source_channel": "KAFKA",
  "country_hint": "AE",
  "raw_address": "Office 1204, Tower 3, Sheikh Zayed Road, Dubai, UAE",
  "structured_address": { ... full PstlAdr-shape ... },
  "field_attributions": [ ... per-field structurer attribution ... ],
  "compliance_routing": {
    "primary_reason": "HIGH_RISK_COUNTRY",
    "all_reasons": ["HIGH_RISK_COUNTRY", "LOW_FIELD_CONFIDENCE"],
    "urgency": "STANDARD",
    "field_confidences": {
      "CTRY": 0.99,
      "TWN_NM": 0.92,
      "STRT_NM": 0.71  
    },
    "overall_confidence": 0.83
  },
  "trace_id": "00f067aa0ba902b7..."
}
```

The compliance flow correlates back via `result_id` and writes its
verdict back to `COMPLIANCE_ROUTING` either directly (database access)
or via a callback channel.

---

## 7. The fail-safe story (this matters)

When the compliance system is unreachable, the choice is conservative
or permissive.

**CONSERVATIVE** (the default and the right answer for trade finance):
- The cascade still completes successfully
- The `COMPLIANCE_ROUTING` row is written with `STATUS='PENDING'` and
  `ROUTED_TO='unreachable'`
- The source channel ack is held back; for Kafka, no manual ack means
  consumer rewind on next poll; for RabbitMQ, the message is nack'd for redelivery
- Effect: the source message is held until compliance is back,
  preventing the payment from proceeding without compliance review

**PERMISSIVE**:
- The cascade completes, the `COMPLIANCE_ROUTING` row is `STATUS='FAILED'`
- The source channel acks normally
- The payment proceeds without compliance review
- Effect: payments keep flowing during a compliance outage but
  high-risk transactions slip through unreviewed

For trade finance, the PERMISSIVE policy is operationally tempting
(payments don't get stuck) and operationally indefensible (post-fact
reviews of payments that should have been screened). The default is
CONSERVATIVE; setting it to PERMISSIVE in production should require
explicit risk sign-off.

---

## 8. Phase 1 vs Phase 2 behaviour

In **Phase 1 (shadow mode)**: set `enrichment.compliance.shadow-mode=true`.
The router still evaluates; rows still get written to `COMPLIANCE_ROUTING`;
no message is actually dispatched to compliance. This gives you data on
what WOULD have routed without disturbing the live payment path.

In **Phase 2 (in-line)**: shadow-mode off; routing is real; compliance
verdicts gate downstream payment processing.

The transition is one config flag flip.

---

## 9. Observability for compliance routing

| Metric | Type | Tags |
|---|---|---|
| `compliance.decisions` | Counter | decision (BYPASS/ROUTE/BLOCK), primary_reason, country |
| `compliance.dispatch.latency` | Timer | destination |
| `compliance.dispatch.outcome` | Counter | outcome (success/timeout/failure) |
| `compliance.verdict.latency` | Timer | (cascade-end → compliance-response) |
| `compliance.verdict.outcome` | Counter | verdict (PASS/REJECT/NEEDS_REVIEW/TIMEOUT) |
| `compliance.high_risk_country` | Counter | country |
| `compliance.pattern_hit` | Counter | pattern_name |

Daily report query in `infra/reports/sql/compliance-routing-by-country.sql`:

```sql
SELECT
    country_hint,
    decision,
    primary_reason,
    COUNT(*)                       AS routings,
    AVG(CASE WHEN compliance_verdict = 'PASS'   THEN 1 ELSE 0 END) AS pass_rate,
    AVG(CASE WHEN compliance_verdict = 'REJECT' THEN 1 ELSE 0 END) AS reject_rate
FROM   TFPM_ADDR_ENRICH.COMPLIANCE_ROUTING
WHERE  created_at > SYSDATE - 7
AND    status = 'RESOLVED'
GROUP  BY country_hint, decision, primary_reason
ORDER  BY country_hint, routings DESC;
```

This is the report compliance and IS&C will look at weekly. High
reject rates per country signal that the structurer is producing
systematically wrong outputs that compliance is catching; low
reject rates signal the routing thresholds are too aggressive.

---

## 10. What this design deliberately does NOT do

- **Does not implement sanctions screening itself.** The router decides
  routing; sanctions screening is the existing JPMC compliance flow's
  job. Splitting concerns is the whole point.
- **Does not block the cascade on compliance latency.** The cascade
  completes and persists in <500ms regardless of compliance system
  responsiveness. Compliance dispatch happens after persistence; if it
  fails, the source channel handles backpressure.
- **Does not make the routing decision configurable per channel.**
  HTTP requests, Kafka events, and MQ messages all go through the same
  compliance evaluation. If a different policy is needed per channel
  (which it shouldn't be), that's a separate router bean.
- **Does not auto-update the high-risk countries or sanctions patterns
  lists.** These are operational artefacts maintained by financial
  crime, with their own change-management process. Engineering provides
  the loading mechanism, not the content.

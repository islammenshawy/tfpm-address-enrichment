# ACCURACY_MEASUREMENT.md

How we actually know whether the cascade is working in production.

This is the production complement to `docs/COUNTRY_STRATEGY.md`. That
document covers pre-deploy accuracy against a golden set; this one covers
ongoing measurement against real flowing data. Both matter; neither is
sufficient alone.

---

## 1. The fundamental constraint

There is no ground truth in production. The whole point of structuring an
address is that the structured form did not previously exist; if you had
it, you wouldn't need the cascade. So when someone asks "how accurate is
the system?", the honest answer is:

- "We measured 87% per-field accuracy on a 400-fixture golden set
  hand-validated by two reviewers on 12 May 2026" — this is real, but
  the snapshot is frozen.
- "On the 4.3M addresses we structured last week, 71% had overall
  calibrated confidence ≥ 0.85, 18% required review, and 11% were
  hand-corrected by makers" — this is real, but it's *proxies* for
  accuracy, not accuracy itself.
- "Our weekly sampling-and-review programme estimates 84% per-field
  accuracy on US, 79% on AE, 67% on CN over the past 4 weeks of
  production data, ±3pp at 95% confidence" — this is the closest you
  get to a real production accuracy number, and it costs reviewer time.

All three are needed. None replaces the others.

---

## 2. The five things we measure

| Layer | What it measures | Cost | Cadence | Source of truth |
|---|---|---|---|---|
| Pre-deploy harness | Accuracy on golden set | One-off + per-tuning-iteration | Per release | Yes (held-out) |
| Confidence proxy | Cascade self-rated confidence | Free | Real-time | No (proxy) |
| Cascade attribution | Which structurer contributed what | Free | Real-time | No (diagnostic) |
| Maker corrections | Operator overrides | Free (operators do this anyway) | Real-time | Partial |
| Sample-based validation | Per-country accuracy estimate | ~30 min/country/week | Weekly | Yes (sampled) |

Layers 2-3 produce dashboards from real data flowing through Oracle.
Layers 4-5 produce dashboards from human verdicts captured in two new
Oracle tables. Layer 1 produces a report from the build pipeline.

---

## 3. What metadata we push and where it lands

Every cascade run produces three kinds of output:

### 3a. The structured result (existing `STRUCTURING_RESULTS` row)

```
RESULT_ID, CORRELATION_ID, SOURCE_CHANNEL, RAW_ADDRESS, COUNTRY_HINT,
FIELDS_JSON, STRUCTURER_TRACE, OVERALL_CONFIDENCE, REQUIRES_REVIEW, CREATED_AT
```

This already exists. `FIELDS_JSON` is the merged final fields;
`STRUCTURER_TRACE` is the per-structurer JSON trace. These are great for
debugging individual records and for round-tripping.

### 3b. Per-field attribution (NEW `FIELD_ATTRIBUTIONS` table)

```
ATTRIBUTION_ID, RESULT_ID, FIELD_NAME, STRUCTURER_NAME,
STRUCTURER_VERSION, RAW_CONFIDENCE, CALIBRATED_CONFIDENCE,
WAS_SELECTED ('Y'/'N'), VALUE, LATENCY_MS,
COUNTRY_HINT, CREATED_AT (denormalised for partitioning + query speed)
```

One row per (result, field, structurer). The denormalisation looks
wasteful; it isn't. JSON queries in Oracle are 5-50x slower than
relational queries on indexed columns. Per-country reporting hits this
table millions of times per day; native columns make those queries
sub-second.

The `WAS_SELECTED` flag tells you which structurer's contribution made
it into the final merged result. With this you can ask:

```sql
-- Which structurer is winning STRT_NM in UAE?
SELECT structurer_name, COUNT(*) wins, AVG(calibrated_confidence) avg_conf
FROM   FIELD_ATTRIBUTIONS
WHERE  country_hint = 'AE'
AND    field_name = 'STRT_NM'
AND    was_selected = 'Y'
AND    created_at > SYSDATE - 7
GROUP  BY structurer_name
ORDER  BY wins DESC;
```

### 3c. Response metadata (returned to caller)

The `EnrichmentResponse` includes:

```json
{
  "result_id": 84592017,
  "correlation_id": "pmt-2026-1112-78a3f4-debtor",
  "source_channel": "KAFKA",
  "country_hint": "AE",
  "overall_confidence": 0.83,
  "requires_review": false,
  "fields": {
    "CTRY":   { "value": "AE",                "confidence": 0.99, "source": "libpostal" },
    "TWN_NM": { "value": "Dubai",             "confidence": 0.92, "source": "swift-crf" },
    "STRT_NM":{ "value": "Sheikh Zayed Road", "confidence": 0.81, "source": "llm" }
  },
  "trace_id": "00f067aa0ba902b7..."
}
```

The `result_id` is the lookup key into Oracle. Any consumer of the
response can later run:

```sql
SELECT * FROM STRUCTURING_RESULTS WHERE RESULT_ID = ?;
SELECT * FROM FIELD_ATTRIBUTIONS  WHERE RESULT_ID = ? ORDER BY field_name, calibrated_confidence DESC;
```

…and see exactly what happened. This is the "vet later from Oracle"
contract.

---

## 4. The two new validation tables

### 4a. `VALIDATION_FEEDBACK` — when humans correct cascade output

Operators in the maker/checker UI override structurer output for
exception-queue items. Every override writes a row here. This is the
single highest-quality signal you get for free in production.

```
FEEDBACK_ID, RESULT_ID, FIELD_NAME, ORIGINAL_VALUE, CORRECTED_VALUE,
CORRECTED_BY, CORRECTED_AT, CORRECTION_REASON, COUNTRY_HINT, VERSION
```

Maker correction rate per (country, field) is the cheapest production
proxy for accuracy. If `STRT_NM` corrections in AE jump from 12% to 28%
week over week, something has drifted.

### 4b. `ACCURACY_SAMPLES` — periodic spot-checks on production data

A scheduled job runs daily. For each Tier 0 country, it samples 10 rows
from `STRUCTURING_RESULTS` (stratified across confidence quartiles so
you don't only sample the easy ones), inserts them into `ACCURACY_SAMPLES`
with `STATUS='PENDING'`, and they appear in the reviewer queue.

A reviewer takes ~2 minutes per row to mark each field correct/incorrect.
That's 20 minutes/country/day — sustainable for two analysts to share.

```
SAMPLE_ID, RESULT_ID, COUNTRY_HINT, SAMPLED_AT, STATUS,
REVIEWER, REVIEWED_AT, PER_FIELD_VERDICT (JSON), NOTES, VERSION
```

`PER_FIELD_VERDICT` example:

```json
{
  "CTRY":    { "verdict": "correct" },
  "TWN_NM":  { "verdict": "correct" },
  "STRT_NM": { "verdict": "wrong", "should_be": "Sheikh Zayed Boulevard" },
  "BLDG_NB": { "verdict": "correct" }
}
```

After 4 weeks of daily sampling at 10/country, you have ~280 verdicts
per country. That's enough to estimate per-field accuracy at ±5pp
confidence intervals — good enough to detect drift, good enough to
report to leadership and IS&C, and **derived from real production data**.

---

## 5. The standard reporting queries

These live in `infra/reports/sql/` and are the canonical queries any
ops dashboard should use. Treat them like API surface; if you change
one, version it.

### `daily-accuracy-by-country.sql`

```sql
-- Per-country, per-field accuracy from human verdicts over rolling 30 days.
-- This is the headline number for IS&C and leadership.
SELECT
    s.country_hint AS country,
    field.field_name,
    COUNT(*)                                                 AS samples_reviewed,
    SUM(CASE WHEN field.verdict = 'correct' THEN 1 ELSE 0 END) AS correct,
    ROUND(AVG(CASE WHEN field.verdict = 'correct' THEN 1.0 ELSE 0.0 END), 3) AS accuracy
FROM TFPM_ADDR_ENRICH.ACCURACY_SAMPLES s,
     JSON_TABLE(s.per_field_verdict, '$.*'
       COLUMNS (
         field_name VARCHAR2(20)  PATH '$.name',
         verdict    VARCHAR2(16)  PATH '$.verdict'
       )) field
WHERE s.status   = 'REVIEWED'
  AND s.reviewed_at > SYSDATE - 30
GROUP BY s.country_hint, field.field_name
ORDER BY s.country_hint, field.field_name;
```

### `structurer-contribution-by-country.sql`

```sql
-- Who's winning what, per country, per field, last 7 days.
-- Drives decisions about cascade routing changes.
SELECT
    country_hint                AS country,
    field_name                  AS field,
    structurer_name             AS structurer,
    COUNT(*)                    AS times_selected,
    AVG(calibrated_confidence)  AS avg_conf,
    AVG(latency_ms)             AS avg_latency_ms
FROM TFPM_ADDR_ENRICH.FIELD_ATTRIBUTIONS
WHERE was_selected = 'Y'
  AND created_at  > SYSDATE - 7
GROUP BY country_hint, field_name, structurer_name
ORDER BY country_hint, field_name, times_selected DESC;
```

### `confidence-distribution-by-country.sql`

```sql
-- Histogram (10 buckets) of overall confidence per country.
-- Drives calibrator retuning decisions.
SELECT
    country_hint                                      AS country,
    FLOOR(overall_confidence * 10) / 10               AS conf_bucket,
    COUNT(*)                                          AS n
FROM   TFPM_ADDR_ENRICH.STRUCTURING_RESULTS
WHERE  created_at > SYSDATE - 7
GROUP  BY country_hint, FLOOR(overall_confidence * 10) / 10
ORDER  BY country_hint, conf_bucket;
```

### `maker-correction-rate-by-country.sql`

```sql
-- Per-country, per-field correction rates over rolling 14 days.
-- Cheap accuracy proxy — high correction rate = degraded accuracy.
SELECT
    f.country_hint                                                                  AS country,
    f.field_name                                                                    AS field,
    COUNT(DISTINCT f.result_id)                                                     AS results_reviewed_by_makers,
    COUNT(*)                                                                        AS total_corrections,
    ROUND(COUNT(*)::numeric / NULLIF(COUNT(DISTINCT f.result_id), 0), 3)            AS corrections_per_review
FROM   TFPM_ADDR_ENRICH.VALIDATION_FEEDBACK f
WHERE  f.corrected_at > SYSDATE - 14
GROUP  BY f.country_hint, f.field_name
ORDER  BY f.country_hint, corrections_per_review DESC;
```

### `weekly-accuracy-trend.sql`

```sql
-- 12-week trend per country. Run weekly. Output goes to leadership PDF.
SELECT
    TRUNC(s.reviewed_at, 'IW')      AS week_starting,
    s.country_hint                  AS country,
    COUNT(*)                        AS samples,
    ROUND(AVG(CASE WHEN JSON_QUERY(s.per_field_verdict, '$.CTRY.verdict')   = 'correct' THEN 1.0 ELSE 0.0 END), 3) AS ctry_acc,
    ROUND(AVG(CASE WHEN JSON_QUERY(s.per_field_verdict, '$.TWN_NM.verdict') = 'correct' THEN 1.0 ELSE 0.0 END), 3) AS twn_nm_acc,
    ROUND(AVG(CASE WHEN JSON_QUERY(s.per_field_verdict, '$.STRT_NM.verdict')= 'correct' THEN 1.0 ELSE 0.0 END), 3) AS strt_nm_acc
FROM TFPM_ADDR_ENRICH.ACCURACY_SAMPLES s
WHERE s.status = 'REVIEWED'
  AND s.reviewed_at > SYSDATE - 84
GROUP BY TRUNC(s.reviewed_at, 'IW'), s.country_hint
ORDER BY week_starting DESC, country;
```

---

## 6. The sampling job

`AccuracySamplingJob` runs daily at 02:00 (off-peak). Pseudocode:

```java
@Component
@ThreadSafe
class AccuracySamplingJob {

    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "accuracy-sampling")  // shedlock — single replica runs
    public void sampleDailyForReview() {
        for (var country : tier0Countries()) {
            // Stratify: 4 from confidence ≥ 0.9, 4 from 0.7-0.9, 2 from < 0.7
            var samples = new ArrayList<Long>();
            samples.addAll(repo.sampleByConfidence(country, 0.9, 1.0,  4));
            samples.addAll(repo.sampleByConfidence(country, 0.7, 0.9,  4));
            samples.addAll(repo.sampleByConfidence(country, 0.0, 0.7,  2));

            samples.forEach(resultId ->
                repo.queueForReview(resultId, country, Instant.now()));
        }
    }
}
```

Stratified sampling means low-confidence samples (the cases we
*expect* to be wrong) don't dominate the verdicts; high-confidence
samples (where we're claiming the system is great) get reviewed too.
This is the difference between an accuracy estimate and an exception-rate
proxy.

---

## 7. Operator workflow for vetting

This is what the user actually does — described in process terms so the
ops team can plan headcount.

### Daily (10 minutes/country, 2 analysts share)

1. Open the maker UI's "Accuracy review" tab
2. See the 10 pending samples per country, ordered by sample date
3. For each: read the raw address, look at the structured fields,
   click ✓ or ✗ per field
4. Submit; the verdict writes to `ACCURACY_SAMPLES.PER_FIELD_VERDICT`

### Weekly (15 minutes, lead analyst)

1. Run `weekly-accuracy-trend.sql` and `maker-correction-rate-by-country.sql`
2. Identify any country whose accuracy dropped > 5pp week-over-week
3. For those: drill into `structurer-contribution-by-country.sql`
4. If a specific structurer's win rate has shifted: open a tuning ticket
5. If a country's overall confidence distribution has skewed left:
   queue a calibrator retune

### Monthly (30 minutes, lead analyst + engineering lead)

1. Compile the per-tier accuracy summary
2. Compare against tier acceptance criteria from `COUNTRY_STRATEGY.md`
3. Identify any country at risk of falling below tier bar
4. Decide: retune, add fixtures, demote tier, or flag for vendor evaluation

---

## 8. Triggers for engineering action

These are the rules the weekly report applies automatically. Each
trigger writes an alert to the on-call channel and opens a ticket.

| Condition | Action |
|---|---|
| Per-country sample-validated accuracy drops > 5pp WoW | Open tuning ticket, prioritise for next sprint |
| Per-country maker correction rate > 30% on any required field | Open tuning ticket, prioritise current sprint |
| Confidence distribution skews > 0.10 in median over 14 days | Investigate calibrator drift |
| A structurer's win rate on a country drops > 20pp in 7 days | Investigate structurer regression (sidecar version, model swap) |
| Tier 0 country accuracy falls below tier-0 bar (80%) for 2 consecutive weeks | Escalate to engineering lead; demotion proposal to leadership |
| Tier 0 country has < 30 reviewed samples in last 30 days | Block sampling-job alerts; reviewer headcount issue |

---

## 9. What this gives stakeholders

Three deliverables, all SQL-backed, none requiring engineering work to
regenerate:

1. **Weekly per-country accuracy email.** Auto-sent Monday 09:00.
   Five lines per Tier 0 country: accuracy %, n samples, WoW delta,
   maker correction rate, exception rate.
2. **Live ops dashboard** (Grafana on top of Oracle). Same metrics,
   real-time. Drill-down by country, field, structurer.
3. **Monthly IS&C / leadership PDF.** Generated from the queries
   above. One page summary, four pages of per-country detail. The
   document IS&C will look at to approve continued operation and
   eventual cutover.

The first two are running by Day 12. The third becomes useful after
roughly 6 weeks of production data accumulation; before that, the
sample sizes are too small for confidence intervals.

---

## 10. What this deliberately does NOT do

- **It does not replace the pre-deploy golden harness.** The harness is
  immune to selection bias (you fix the inputs); production sampling
  has whatever bias the production traffic has.
- **It does not produce accuracy numbers in the first 4 weeks of
  production.** Sample sizes are too small. The first month is
  proxy-only (confidence distribution, exception rates, maker corrections).
- **It does not auto-correct.** A field flagged "wrong" by a reviewer is
  recorded in `ACCURACY_SAMPLES` but does not modify `STRUCTURING_RESULTS`.
  Auto-correction creates feedback loops that destroy your ability to
  measure accuracy. Corrections that need to flow back to the source
  system are a separate downstream workstream.
- **It does not measure recall.** The cascade can only fail to populate
  fields, not to find addresses that should have been processed. If TPS
  has 100 addresses but the Kafka tee delivered 95 of them, this
  measurement framework cannot detect the missing 5. That's a
  data-pipeline-completeness concern, not an accuracy concern.

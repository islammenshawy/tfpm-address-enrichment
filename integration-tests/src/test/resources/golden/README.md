# Golden-set fixtures

Each Tier 0 country has its own subdirectory with hand-validated
fixtures. The file format is the same across countries; the contents are
country-specific.

Tier 0 (mandatory before UAT): AE, SG, HK, CN, GB, US, DE, CH

Tier 1 (required before prod cutover): SA, IN, BR, ZA, JP, KR, NL, BE,
FR, IT, AU, CA

Tier 2 (best effort): everything else

## Required minimums per tier

| Tier | Min hand-validated fixtures per country |
|---|---|
| Tier 0 | 50 |
| Tier 1 | 25 |
| Tier 2 | 10 |

The accuracy harness fails the build if a Tier 0 country has fewer than
50 fixtures. There is no way to ship without per-country evidence.

## File format

Each fixture is one JSON file at `<COUNTRY>/<sequence>.json`:

```json
{
  "fixture_id": "AE-001",
  "country": "AE",
  "source": "legacy_oracle | kafka | mq",
  "raw": "Office 1204, Tower 3, Sheikh Zayed Road, Dubai, United Arab Emirates",
  "country_hint": "AE",
  "locale": "en-AE",
  "expected_fields": {
    "CTRY":          { "value": "AE",                   "required": true  },
    "CTRY_SUB_DVSN": { "value": "Dubai",                "required": true  },
    "TWN_NM":        { "value": "Dubai",                "required": true  },
    "STRT_NM":       { "value": "Sheikh Zayed Road",    "required": true  },
    "BLDG_NM":       { "value": "Tower 3",              "required": false },
    "BLDG_NB":       { "value": "1204",                 "required": false },
    "PST_CD":        { "value": "",                     "required": false }
  },
  "validator": "name@jpmorgan.com",
  "validated_at": "2026-05-12",
  "notes": "PO Box culture means many AE corporate addresses lack BldgNb; this one has an office number used as BldgNb."
}
```

## Sourcing guidance

- **legacy_oracle**: 60% of each country's fixtures should come from the
  legacy Oracle backfill, sampled stratified by source-system tag if
  possible. This is where the messy historical data lives and where the
  service must perform.
- **kafka**: 25% from real-time Kafka payment events. These tend to be
  cleaner (modern self-service entry).
- **mq**: 15% from MQ corporate ingestion. Legacy MT format quirks live here.

## Validation process

1. Random sample N rows from each source per country (use the SQL in
   `docs/DATA_SOURCES.md` section 2)
2. Open in a spreadsheet with one column per field
3. Two reviewers fill in expected fields independently
4. Reconcile disagreements; both must agree on final value
5. Commit fixtures with `validator` field naming the reconciler

This is unglamorous work. It is also the single highest-leverage
activity for hitting per-country accuracy targets. Skipping it because
"the model will figure it out" is the most common reason these projects
miss their accuracy bars.

## Anti-patterns

- **Synthetic golden sets.** Do not let an LLM generate the expected
  fields. The whole point is to measure the cascade against ground truth
  that humans validated.
- **Cherry-picking easy examples.** If your AE fixtures are all DIFC
  corporate addresses, your accuracy number is meaningless for the bulk
  of UAE flows. Sample stratified across the actual TPS data distribution.
- **Reusing fixtures across iterations.** Once a fixture has been used
  to tune a calibrator, its value as a measurement drops. Hold back at
  least 30% of each country's fixtures as a held-out test set never
  used during tuning.

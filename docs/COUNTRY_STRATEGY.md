# COUNTRY_STRATEGY.md

Per-country accuracy is where most of the residual risk lives once the
cascade plumbing is solid. This document is the strategy for handling
country variation as a structural concern, not as a Day-8 surprise.

Read alongside `docs/DATA_SOURCES.md` (which covers where addresses come
from) and `docs/LLM_MODEL_INTEGRATION.md` (which covers prompt management).

---

## 1. Why per-country matters more than the cascade

Three uncomfortable facts:

1. **libpostal accuracy varies by 25–40 percentage points across
   countries.** It was trained on a corpus dominated by Western data.
   Out of the box it handles US/DE/FR/UK well, struggles on UAE, badly
   on CN/JP/KR.
2. **LLM training data is English-dominant.** Even the strongest models
   degrade on transliterated, mixed-script, or non-Western-format
   addresses. Few-shot examples help but don't close the gap.
3. **TPS data quality varies by source country.** Addresses migrated
   from regional acquisitions are messier than ones captured natively
   in modern TPS. UAE addresses sourced from a 2014 migration look
   nothing like a 2024 self-service-entered Singapore one.

The cascade plumbing in this bundle does not fix any of this. It gives
you the surface to plug country-specific tuning into without rewriting
anything.

## 2. TFPM country tiers (NOT generic G20)

Trade finance has its own geography. The tier list below reflects TFPM
volume, not global GDP rankings.

### Tier 0 — Cannot fail

These are the corridors where degraded accuracy directly costs trade
finance revenue. Mandatory ≥ 80% per-field accuracy before UAT cutover.

| Country | Reason | Typical pain |
|---|---|---|
| **AE** (UAE) | DIFC entities, Gulf hub | Emirate as state, Arabic/English mix, PO Box culture, free zones |
| **SG** (Singapore) | Regional booking hub | Block-Unit-Postal format, HDB vs private |
| **HK** (Hong Kong) | China gateway | Floor/Unit patterns, no postal codes traditionally |
| **CN** (Mainland China) | Counterparty volume | Reverse-order addressing, mixed-script |
| **GB** (UK) | London booking | Postcode format, missing street numbers |
| **US** | Cleared USD flows | Suite notation, ZIP+4 |
| **DE** (Germany) | EUR settlements | Straße abbreviations, postcode-before-city |
| **CH** (Switzerland) | Trade finance hub | Multi-language (DE/FR/IT), canton |

### Tier 1 — Important, slightly lower bar

≥ 75% per-field accuracy required.

`SA, IN, BR, ZA, JP, KR, NL, BE, FR, IT, AU, CA`

### Tier 2 — Best effort

≥ 65% acceptable. Document known limitations; do not block cutover.

Everything else.

### Stratified golden-set targets

| Tier | Min hand-validated samples per country | Sources |
|---|---|---|
| Tier 0 | 50 | Mix: 25 from legacy Oracle, 15 from Kafka, 10 from MQ |
| Tier 1 | 25 | Mix: 15 from legacy Oracle, 10 from runtime channels |
| Tier 2 | 10 | Best available |

Without these minimums, accuracy numbers are not statistically meaningful
and IS&C review will (correctly) push back.

## 3. Tier 0 country playbooks

The concrete things to do per country. These are the differences between
"libpostal returned something" and "the structured output is correct."

### UAE (AE)

**Pain points**

- "Sheikh Zayed Road" / "Sheikh Zayed Rd" / "SZR" — same street, three forms
- Emirate as `CTRY_SUB_DVSN` not `TWN_NM` (Dubai is the emirate AND the city in TPS data)
- PO Box addresses dominate corporate counterparties: "P.O. Box 12345, Dubai, UAE"
- Free zones (DIFC, JAFZA, DAFZA, DMCC) have their own conventions and act
  as both `BLDG_NM` and `CTRY_SUB_DVSN`
- Mixed Arabic/English transliteration variants: "Al Maktoum" / "Maktum" / "Maktoum"
- Office/Tower/Building prefix chains: "Office 1204, Tower 3, Emirates Towers"

**Mitigations** (in priority order)

1. Pre-processor in `LibpostalAddressStructurer` that normalises top-50
   road and area name variants from a `data/ae-normalisations.csv`
2. Country-aware LLM prompt with 6+ UAE-specific few-shot examples
   (DIFC corp, Dubai PO Box, Abu Dhabi residential, free zone)
3. Explicit emirate enum constraint in JSON output schema
4. Post-processor recognises "P.O. Box" patterns and extracts the box
   number into `BLDG_NB` while leaving `STRT_NM` empty
5. Calibrator: separate UAE column in confidence calibration table,
   tuned against UAE-only golden subset

### Singapore (SG)

**Pain points**

- Strict format: `Block-Street-Unit-Postal` (e.g. "Block 123, Anson Road, #12-34, Singapore 079903")
- HDB blocks vs private buildings have different patterns
- 6-digit postal codes are reliable when present; absence is meaningful
- "#XX-YY" unit notation must extract correctly to `BLDG_NM` or stay in `ADR_LINE`

**Mitigations**

1. Regex pre-processor for `#XX-YY` unit pattern → diagnostic field, leave in `ADR_LINE`
2. 6-digit postal code regex → `PST_CD` directly, high confidence
3. "Block N" / "Blk N" / "BLK N" pattern → `BLDG_NB`
4. LLM few-shots include 4 SG variants (HDB, condo, commercial tower, industrial)

### Hong Kong (HK)

**Pain points**

- No postal codes (mostly), so absence of `PST_CD` is correct, not missing
- Floor/Unit patterns: "27/F", "Flat A 27/F", "Unit 2701, 27/F"
- Building name is essential and often contains street name: "Two IFC, 8 Finance Street"
- District (Wan Chai, Central, Kowloon) maps to `CTRY_SUB_DVSN`

**Mitigations**

1. Pre-processor extracts floor/unit pattern → `BLDG_NM` component
2. District lookup table → `CTRY_SUB_DVSN` (~30 entries)
3. Calibrator: `PST_CD` confidence weight = 0 for HK (don't penalise absence)
4. LLM few-shots demonstrate building-name-contains-everything pattern

### China (CN)

**Pain points**

- Reverse-order addressing: province → city → district → road → number
- libpostal trained left-to-right; near-useless on CN as-is
- Pinyin vs English vs Hanzi: same address may appear in three scripts
- Province names lengthy: "广东省" / "Guangdong Province" / "Guangdong Sheng"

**Mitigations** (this is the hardest)

1. **Skip libpostal for CN** via `CountryRouter`. libpostal noise outweighs signal.
2. Route directly to LLM with CN-specific reverse-parsing prompt
3. Province + city normalisation table (~600 entries — derived from
   ISO 3166-2:CN, automatable)
4. Accept lower confidence floor for CN (review threshold 0.65, not 0.70)
5. Long-term: evaluate vendor product (Baidu/Amap geocoding API) as a
   structurer for CN only, gated behind dedicated entitlement

### UK (GB)

**Pain points**

- Postcode format is well-defined and high-signal: 1-2 letters, 1-2 digits,
  optional space, 1 digit, 2 letters
- Many corporate addresses lack street numbers ("Standard Chartered, Aldermanbury Square")
- Flat/Apartment notation: "Flat 4, 12 Some Road" vs "12 Some Road, Flat 4"

**Mitigations**

1. Postcode regex pre-extract → `PST_CD` with confidence 0.95+
2. Country detection from postcode alone is reliable; populate `CTRY=GB`
3. libpostal handles UK well; mainly need flat-notation post-processing

### US

**Pain points**

- libpostal handles US well — this is the easy country
- Watch for ZIP+4 (postal `12345-6789`) handling
- Suite/Apt/Ste/# variants in `BLDG_NM`

**Mitigations**

1. Calibrator: high baseline confidence for US (libpostal trained heavily on it)
2. Standardise Suite/Apt/Ste → `BLDG_NM` consistent value

### Germany (DE)

**Pain points**

- Straße / Str. / Strasse / -str variants
- House number suffix: "10a", "10-12", "10/2"
- Postcode (5 digits) before city, not after

**Mitigations**

1. Pre-processor normalises Straße abbreviations
2. Regex captures house number with optional suffix → `BLDG_NB`
3. Postcode-before-city is libpostal's natural format; mostly works

### Switzerland (CH)

**Pain points**

- Tri-lingual (DE-CH, FR-CH, IT-CH); same place may appear in any
- Canton abbreviations: "Zürich" / "ZH" / "Zurich"
- 4-digit postcode (not 5)

**Mitigations**

1. Locale hint critical: detect from address language, route LLM accordingly
2. Canton lookup table (26 entries)
3. Distinguish from DE/AT in country detection (4-digit postcode → CH)

## 4. The country router

Add `CountryRouter` to `domain/`:

```java
public interface CountryRouter {
    /** Filter the cascade for a known country. Empty list = use full cascade. */
    List<String> structurersFor(String countryHint);
}
```

Default implementation returns empty list (use everything). Production
config flips off known-weak combinations:

```yaml
enrichment:
  cascade:
    routing:
      CN: [llm]                     # skip libpostal entirely
      JP: [llm, swift-crf]          # skip libpostal
      KR: [llm, swift-crf]          # skip libpostal
      US: [libpostal, swift-crf]    # skip LLM (overkill, costly)
      GB: [libpostal, swift-crf]    # skip LLM
      DE: [libpostal, swift-crf]    # skip LLM
      # Tier 0 trade finance: full cascade (default)
      AE: [libpostal, swift-crf, llm]
      SG: [libpostal, swift-crf, llm]
      HK: [libpostal, swift-crf, llm]
```

The router is **opt-in optimisation**, not a hard requirement. Wire it
in once you have evidence from the accuracy harness about which
combinations help and which add noise.

## 5. Country-aware LLM prompt

The `LlmAddressStructurer` injects country-specific guidance when the
country hint is known:

```
SYSTEM:
  You structure postal addresses to ISO 20022 PstlAdr fields...

  This address is from {{country}}. Apply these conventions:
  {{country-specific guidance from prompts/countries/{{country}}.md}}

  Output JSON matching the schema...

USER:
  Address: {{raw}}
  Country hint: {{country}}
  Locale: {{locale}}
```

The per-country guidance lives in `app/src/main/resources/prompts/countries/`
as one short Markdown file per country, hand-edited based on accuracy
harness findings:

```
prompts/countries/AE.md
prompts/countries/CN.md
prompts/countries/SG.md
...
```

Editing a country guidance file does not require a code change or
redeploy if you set up the prompt loader to refresh on file change
(default: load once at startup; sufficient for week-1 use).

## 6. Per-country calibration tables

Confidence calibrators load per-country tables at startup:

```yaml
calibration:
  libpostal:
    countries-csv: classpath:calibration/libpostal-by-country.csv
    default-curve: identity
```

CSV format: `country,field,raw_low,raw_high,calibrated_low,calibrated_high`
(piecewise linear). One row per (country, field) pair. Missing entries
fall back to the default curve.

Day 1: identity calibrators (raw passed through). Day 8-9: real
calibration learned from the accuracy harness golden set, committed as
CSV updates with no code change.

## 7. Acceptance criteria by tier

| Tier | Per-field accuracy bar | Action if below |
|---|---|---|
| Tier 0 | ≥ 80% on every required field per country | Block UAT cutover; iterate normalisers + prompt |
| Tier 1 | ≥ 75% averaged across countries | Document and proceed; revisit before prod cutover |
| Tier 2 | ≥ 65% averaged | Document known limitations |

The accuracy harness emits a per-country report; the build can fail if
Tier 0 thresholds are not met (set as `mvn -P accuracy verify` gate).

## 8. ArchUnit invariant for golden coverage

Add to `ArchitectureTest`:

```java
@ArchTest
static final ArchRule tier_zero_countries_have_golden_fixtures =
    classes()
        .that()...   // fixture-loader registry
        .should(haveFixturesFor(Set.of(
            "AE","SG","HK","CN","GB","US","DE","CH")))
        .because("Tier 0 trade-finance countries cannot ship without "
                + "hand-validated golden set coverage");
```

This catches the failure mode where someone "tunes" the cascade by
removing or never adding a problematic country from the test set.

## 9. The escalation path when a country fails baseline

Iterate in this order; stop at the first one that gets you over the bar.

1. **Add or refine country-specific normalisations** (24-48h work).
   Pre-processor regex, lookup tables, abbreviation expansion.
2. **Add or refine LLM country prompt** (4-8h work).
   Edit the per-country Markdown guidance, add 2-3 new few-shot examples.
3. **Tune per-country calibrator** (4-8h work).
   Adjust calibration table to weight the country's strong fields higher.
4. **Configure routing to skip known-weak structurers** (1h work).
   Update `enrichment.cascade.routing.<country>` in `application.yml`.
5. **Lower the threshold for the country and document why** (governance work).
   Accept the tier-1 bar (75%) for a country that should be tier-0,
   with a written justification.
6. **Last resort: country-specific structurer.** Vendor product (Loqate
   for UAE, Baidu for CN), exposed via the same `AddressStructurer`
   contract, gated behind dedicated entitlement and budget.

The point of this list is that you have **five increasingly cheap
options before you have to do anything expensive**. Most countries that
fail on Day 8 hit baseline on Day 9 with options 1+2 alone.

## 10. What this means for the schedule

Per-country tuning concentrates in Days 8-9 of the original plan. The
bundle's structural support (router, country prompts, per-country
calibrators, fixture directory) means those days are tight, focused
iteration rather than open-ended discovery.

Realistic outcomes with the strategy in this document:

- **Tier 0 western countries (US, GB, DE, CH)**: hit baseline on Day 8,
  no surprises.
- **Tier 0 trade finance (AE, SG, HK)**: hit baseline on Day 9 if the
  golden set is in place. May need a calibration cycle.
- **CN**: realistic risk of needing more than 2 days. Plan for Day 8-10
  with the routing-to-LLM-only fallback as a backstop.

If CN doesn't hit baseline by Day 10, ship it as Tier 1 (75%) for v1
with a documented intent to add a country-specific vendor structurer
in a follow-on workstream. Do not block UAT cutover on CN-specific
perfection.

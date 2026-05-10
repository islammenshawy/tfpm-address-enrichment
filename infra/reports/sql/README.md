# Reporting SQL

Canonical queries for the production accuracy reports defined in
`docs/ACCURACY_MEASUREMENT.md`. These are API surface — version them
when you change them; downstream Grafana dashboards and the weekly
email job reference them by file name.

| File | Purpose | Cadence |
|---|---|---|
| `daily-accuracy-by-country.sql` | Per-country, per-field accuracy from human verdicts (rolling 30 days) | Daily |
| `structurer-contribution-by-country.sql` | Who's winning what, last 7 days | Daily |
| `confidence-distribution-by-country.sql` | 10-bucket histogram of overall confidence | Daily |
| `maker-correction-rate-by-country.sql` | Correction rates (rolling 14 days) | Daily |
| `weekly-accuracy-trend.sql` | 12-week trend per country | Weekly |
| `exception-queue-rate.sql` | Low-confidence rates by country | Daily |

Run as the `TFPM_ADDR_ENRICH_APP` user (read-only on the underlying tables
through the schema-owner SELECT grants in changelog 009).

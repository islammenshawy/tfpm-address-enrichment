-- maker-correction-rate-by-country.sql
-- Per-country, per-field correction rates over rolling 14 days.
-- Cheap accuracy proxy — high correction rate = degraded accuracy.

SELECT
    f.country_hint                                                          AS country,
    f.field_name                                                            AS field,
    COUNT(DISTINCT f.result_id)                                             AS results_reviewed,
    COUNT(*)                                                                AS total_corrections,
    ROUND(COUNT(*) / NULLIF(COUNT(DISTINCT f.result_id), 0), 3)             AS corrections_per_review
FROM   TFPM_ADDR_ENRICH.VALIDATION_FEEDBACK f
WHERE  f.corrected_at > SYSDATE - 14
GROUP  BY f.country_hint, f.field_name
ORDER  BY f.country_hint, corrections_per_review DESC;

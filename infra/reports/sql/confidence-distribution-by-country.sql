-- confidence-distribution-by-country.sql
-- 10-bucket histogram of overall confidence per country, last 7 days.
-- Drives calibrator retuning decisions; left-skew = degradation.

SELECT
    country_hint                                AS country,
    FLOOR(overall_confidence * 10) / 10         AS conf_bucket,
    COUNT(*)                                    AS n
FROM   TFPM_ADDR_ENRICH.STRUCTURING_RESULTS
WHERE  created_at > SYSDATE - 7
GROUP  BY country_hint, FLOOR(overall_confidence * 10) / 10
ORDER  BY country_hint, conf_bucket;

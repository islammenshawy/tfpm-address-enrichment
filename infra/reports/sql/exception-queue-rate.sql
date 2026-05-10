-- exception-queue-rate.sql
-- Per-country, per-day exception rate. Sustained high rates indicate either
-- model degradation or a data-quality shift in the upstream source.

SELECT
    TRUNC(r.created_at)                                             AS date,
    r.country_hint                                                  AS country,
    COUNT(*)                                                        AS total_processed,
    SUM(CASE WHEN r.requires_review = 'Y' THEN 1 ELSE 0 END)        AS sent_to_exception,
    ROUND(AVG(CASE WHEN r.requires_review = 'Y' THEN 1.0 ELSE 0.0 END), 3) AS exception_rate
FROM   TFPM_ADDR_ENRICH.STRUCTURING_RESULTS r
WHERE  r.created_at > SYSDATE - 14
GROUP  BY TRUNC(r.created_at), r.country_hint
ORDER  BY date DESC, country;

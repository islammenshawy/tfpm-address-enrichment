-- structurer-contribution-by-country.sql
-- Who's winning what, per country, per field, last 7 days.
-- Drives decisions about cascade routing changes.

SELECT
    country_hint                AS country,
    field_name                  AS field,
    structurer_name             AS structurer,
    COUNT(*)                    AS times_selected,
    ROUND(AVG(calibrated_confidence), 3)  AS avg_calibrated_conf,
    ROUND(AVG(latency_ms), 0)             AS avg_latency_ms
FROM TFPM_ADDR_ENRICH.FIELD_ATTRIBUTIONS
WHERE was_selected = 'Y'
  AND created_at  > SYSDATE - 7
GROUP BY country_hint, field_name, structurer_name
ORDER BY country_hint, field_name, times_selected DESC;

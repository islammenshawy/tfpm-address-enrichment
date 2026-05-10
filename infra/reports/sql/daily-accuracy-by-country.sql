-- daily-accuracy-by-country.sql
-- Per-country, per-field accuracy from human verdicts over rolling 30 days.
-- Headline number for IS&C and leadership.

SELECT
    s.country_hint                                                              AS country,
    field.field_name                                                            AS field,
    COUNT(*)                                                                    AS samples_reviewed,
    SUM(CASE WHEN field.verdict = 'correct' THEN 1 ELSE 0 END)                  AS correct,
    ROUND(AVG(CASE WHEN field.verdict = 'correct' THEN 1.0 ELSE 0.0 END), 3)    AS accuracy
FROM TFPM_ADDR_ENRICH.ACCURACY_SAMPLES s,
     JSON_TABLE(s.per_field_verdict, '$.*'
       COLUMNS (
         field_name VARCHAR2(20)  PATH '$.name',
         verdict    VARCHAR2(16)  PATH '$.verdict'
       )) field
WHERE s.status      = 'REVIEWED'
  AND s.reviewed_at > SYSDATE - 30
GROUP BY s.country_hint, field.field_name
ORDER BY s.country_hint, field.field_name;

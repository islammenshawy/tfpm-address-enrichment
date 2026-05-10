-- weekly-accuracy-trend.sql
-- 12-week trend per country. Run weekly. Output goes to leadership PDF.

SELECT
    TRUNC(s.reviewed_at, 'IW')                                                                          AS week_starting,
    s.country_hint                                                                                      AS country,
    COUNT(*)                                                                                            AS samples,
    ROUND(AVG(CASE WHEN JSON_QUERY(s.per_field_verdict, '$.CTRY.verdict')    = 'correct' THEN 1.0 ELSE 0.0 END), 3) AS ctry_acc,
    ROUND(AVG(CASE WHEN JSON_QUERY(s.per_field_verdict, '$.TWN_NM.verdict')  = 'correct' THEN 1.0 ELSE 0.0 END), 3) AS twn_nm_acc,
    ROUND(AVG(CASE WHEN JSON_QUERY(s.per_field_verdict, '$.STRT_NM.verdict') = 'correct' THEN 1.0 ELSE 0.0 END), 3) AS strt_nm_acc,
    ROUND(AVG(CASE WHEN JSON_QUERY(s.per_field_verdict, '$.PST_CD.verdict')  = 'correct' THEN 1.0 ELSE 0.0 END), 3) AS pst_cd_acc
FROM TFPM_ADDR_ENRICH.ACCURACY_SAMPLES s
WHERE s.status      = 'REVIEWED'
  AND s.reviewed_at > SYSDATE - 84
GROUP BY TRUNC(s.reviewed_at, 'IW'), s.country_hint
ORDER BY week_starting DESC, country;

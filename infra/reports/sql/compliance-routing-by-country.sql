-- compliance-routing-by-country.sql
-- Per-country, per-decision compliance routing summary, last 7 days.
-- Run weekly. The report compliance and IS&C will look at.
--
-- High reject_rate per country signals the structurer is producing
-- systematically wrong outputs that compliance is catching; low reject
-- rates signal the routing thresholds are too aggressive.

SELECT
    country_hint                                                                            AS country,
    decision,
    primary_reason,
    COUNT(*)                                                                                AS routings,
    ROUND(AVG(CASE WHEN compliance_verdict = 'PASS'         THEN 1.0 ELSE 0.0 END), 3)      AS pass_rate,
    ROUND(AVG(CASE WHEN compliance_verdict = 'REJECT'       THEN 1.0 ELSE 0.0 END), 3)      AS reject_rate,
    ROUND(AVG(CASE WHEN compliance_verdict = 'NEEDS_REVIEW' THEN 1.0 ELSE 0.0 END), 3)      AS needs_review_rate,
    ROUND(AVG(EXTRACT(SECOND FROM (compliance_responded_at - routed_at))
            + EXTRACT(MINUTE FROM (compliance_responded_at - routed_at)) * 60), 1)          AS avg_compliance_latency_sec
FROM   TFPM_ADDR_ENRICH.COMPLIANCE_ROUTING
WHERE  created_at > SYSDATE - 7
AND    status = 'RESOLVED'
GROUP  BY country_hint, decision, primary_reason
ORDER  BY country_hint, routings DESC;

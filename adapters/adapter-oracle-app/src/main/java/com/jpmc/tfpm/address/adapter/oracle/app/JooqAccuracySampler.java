package com.jpmc.tfpm.address.adapter.oracle.app;

import com.jpmc.tfpm.address.domain.AccuracySampler;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Set;

import static org.jooq.impl.DSL.*;

@ThreadSafe
public final class JooqAccuracySampler implements AccuracySampler {

    private static final Logger LOG = LoggerFactory.getLogger(JooqAccuracySampler.class);
    private static final Set<String> TIER_0 = Set.of("AE", "SG", "HK", "CN", "GB", "US", "DE", "CH");

    private final DSLContext dsl;

    public JooqAccuracySampler(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public int sampleForReview() {
        var now = LocalDateTime.now();
        var yesterday = now.minusDays(1);
        int total = 0;

        for (var country : TIER_0) {
            total += sampleBucket(country, "HIGH", 0.9, 1.01, 4, yesterday, now);
            total += sampleBucket(country, "MID", 0.7, 0.9, 4, yesterday, now);
            total += sampleBucket(country, "LOW", 0.0, 0.7, 2, yesterday, now);
        }

        LOG.info("Accuracy sampling complete: {} samples queued", total);
        return total;
    }

    private int sampleBucket(String country, String bucket, double minConf, double maxConf,
                              int sampleSize, LocalDateTime from, LocalDateTime to) {
        try {
            var candidates = dsl.select(field("RESULT_ID"))
                    .from(table("STRUCTURING_RESULTS"))
                    .where(field("COUNTRY_HINT").eq(country))
                    .and(field("OVERALL_CONFIDENCE").ge(minConf))
                    .and(field("OVERALL_CONFIDENCE").lt(maxConf))
                    .and(field("CREATED_AT").between(from, to))
                    .orderBy(function("DBMS_RANDOM.VALUE", Double.class))
                    .limit(sampleSize)
                    .fetch(field("RESULT_ID", Long.class));

            int inserted = 0;
            for (var resultId : candidates) {
                dsl.insertInto(table("ACCURACY_SAMPLES"))
                        .columns(field("RESULT_ID"), field("COUNTRY_HINT"),
                                field("CONFIDENCE_BUCKET"), field("STATUS"), field("VERSION"))
                        .values(resultId, country, bucket, "PENDING", 0)
                        .execute();
                inserted++;
            }
            return inserted;
        } catch (Exception e) {
            LOG.error("Sampling failed for {} {}: {}", country, bucket, e.getMessage());
            return 0;
        }
    }
}

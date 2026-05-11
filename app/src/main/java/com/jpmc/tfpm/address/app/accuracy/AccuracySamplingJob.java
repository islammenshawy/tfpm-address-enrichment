package com.jpmc.tfpm.address.app.accuracy;

import com.jpmc.tfpm.address.domain.AccuracySampler;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Daily sampling job for production accuracy measurement.
 * Delegates to {@link AccuracySampler} port (implemented in adapter-oracle-app).
 * Runs daily at 02:00. In multi-replica deployments, duplicate execution
 * is harmless (sampling is idempotent). For single-execution guarantee,
 * add ShedLock dependency and @SchedulerLock annotation.
 */
@ThreadSafe
public final class AccuracySamplingJob {

    private static final Logger LOG = LoggerFactory.getLogger(AccuracySamplingJob.class);

    private final AccuracySampler sampler;

    public AccuracySamplingJob(AccuracySampler sampler) {
        this.sampler = sampler;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void run() {
        LOG.info("Starting daily accuracy sampling");
        try {
            var count = sampler.sampleForReview();
            LOG.info("Accuracy sampling complete: {} samples queued for review", count);
        } catch (Exception e) {
            LOG.error("Accuracy sampling job failed", e);
        }
    }
}

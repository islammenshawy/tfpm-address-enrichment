package com.jpmc.tfpm.address.app.backfill;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.LegacyAddressCursor;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Batch backfill job that reads unstructured addresses from the legacy
 * Oracle schema via {@link LegacyAddressCursor} and processes them
 * through the enrichment pipeline.
 *
 * <p>Natural idempotency via the IDEMPOTENCY_KEYS table means this job
 * can be safely restarted — already-processed addresses are skipped.
 */
@ThreadSafe
public final class LegacyBackfillJob {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyBackfillJob.class);
    private static final int LOG_INTERVAL = 10_000;

    private final LegacyAddressCursor cursor;
    private final AddressEnrichmentService enrichmentService;

    public LegacyBackfillJob(LegacyAddressCursor cursor,
                              AddressEnrichmentService enrichmentService) {
        this.cursor = cursor;
        this.enrichmentService = enrichmentService;
    }

    /**
     * Run the backfill. Processes all legacy addresses.
     *
     * @return number of addresses processed
     */
    public long run() {
        LOG.info("Starting legacy backfill");
        var processed = new AtomicLong(0);
        var success = new AtomicLong(0);
        var duplicates = new AtomicLong(0);
        var errors = new AtomicLong(0);

        cursor.streamAll(addr -> {
            try {
                var request = new EnrichmentRequest(
                        "backfill-" + addr.partyId(),
                        EnrichmentRequest.SourceChannel.HTTP,
                        new RawAddress(addr.raw(), addr.countryHint(), ""));

                var result = enrichmentService.enrich(request);
                var count = processed.incrementAndGet();

                switch (result.outcome()) {
                    case SUCCESS, REQUIRES_REVIEW -> success.incrementAndGet();
                    case PERSISTED_DUPLICATE -> duplicates.incrementAndGet();
                    case UNSTRUCTURABLE -> errors.incrementAndGet();
                }

                if (count % LOG_INTERVAL == 0) {
                    LOG.info("Backfill progress: {} processed, {} success, {} dup, {} err",
                            count, success.get(), duplicates.get(), errors.get());
                }
            } catch (Exception e) {
                errors.incrementAndGet();
                LOG.warn("Backfill error: {}", e.getMessage());
            }
        });

        LOG.info("Backfill complete: {} processed, {} success, {} dup, {} err",
                processed.get(), success.get(), duplicates.get(), errors.get());
        return processed.get();
    }
}

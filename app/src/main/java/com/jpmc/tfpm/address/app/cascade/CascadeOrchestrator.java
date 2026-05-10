package com.jpmc.tfpm.address.app.cascade;

import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.CascadeResult;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.CountryRouter;
import com.jpmc.tfpm.address.domain.EnrichmentError;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.Result;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runs the structurer cascade: invokes each structurer in order, checks
 * for early exit, and merges results via {@link FieldMerger}.
 */
@ThreadSafe
public final class CascadeOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(CascadeOrchestrator.class);

    private final List<AddressStructurer> structurers;
    private final FieldMerger fieldMerger;
    private final CountryRouter countryRouter;
    private final double earlyExitThreshold;
    private final Set<AddressField> requiredFields;

    public CascadeOrchestrator(
            List<AddressStructurer> structurers,
            FieldMerger fieldMerger,
            CountryRouter countryRouter,
            double earlyExitThreshold) {
        this.structurers = List.copyOf(structurers);
        this.fieldMerger = fieldMerger;
        this.countryRouter = countryRouter;
        this.earlyExitThreshold = earlyExitThreshold;
        this.requiredFields = Set.of(AddressField.CTRY, AddressField.TWN_NM);
    }

    /**
     * Run the cascade for a single raw address.
     *
     * @param raw           the address to structure
     * @param correlationId for logging
     * @return cascade result or failure if no structurer produced usable fields
     */
    public Result<CascadeResult> orchestrate(RawAddress raw, String correlationId) {
        var applicableStructurers = filterByCountry(raw.countryHint());
        if (applicableStructurers.isEmpty()) {
            return Result.failure(EnrichmentError.of(
                    EnrichmentError.Category.CASCADE_NO_RESULT,
                    "No structurers available for country hint '" + raw.countryHint() + "'",
                    correlationId));
        }

        var trace = new ArrayList<StructuringResult>();

        for (var structurer : applicableStructurers) {
            try {
                var result = structurer.structure(raw);
                trace.add(result);
                LOG.debug("Structurer '{}' returned {} fields in {}ms [corrId={}]",
                        structurer.name(), result.fields().size(),
                        result.latency().toMillis(), correlationId);

                if (shouldEarlyExit(trace, raw.countryHint())) {
                    LOG.debug("Early exit after '{}' — required fields above threshold [corrId={}]",
                            structurer.name(), correlationId);
                    break;
                }
            } catch (Exception e) {
                LOG.warn("Structurer '{}' threw unexpectedly [corrId={}]: {}",
                        structurer.name(), correlationId, e.getMessage());
                // Continue to next structurer — cascade is resilient
            }
        }

        if (trace.isEmpty()) {
            return Result.failure(EnrichmentError.of(
                    EnrichmentError.Category.CASCADE_NO_RESULT,
                    "All structurers failed or returned no results",
                    correlationId));
        }

        var merged = fieldMerger.merge(trace, raw.countryHint());
        double confidence = merged.overallConfidence();

        return Result.success(new CascadeResult(merged, trace, confidence));
    }

    private List<AddressStructurer> filterByCountry(String countryHint) {
        var allowed = countryRouter.structurersFor(countryHint);
        if (allowed.isEmpty()) {
            return structurers; // empty list from router means "use full cascade"
        }
        return structurers.stream()
                .filter(s -> allowed.contains(s.name()))
                .toList();
    }

    private boolean shouldEarlyExit(List<StructuringResult> trace, String countryHint) {
        var merged = fieldMerger.merge(trace, countryHint);
        for (var field : requiredFields) {
            var fv = merged.get(field);
            if (fv.isEmpty() || fv.get().confidence() < earlyExitThreshold) {
                return false;
            }
        }
        return true;
    }
}

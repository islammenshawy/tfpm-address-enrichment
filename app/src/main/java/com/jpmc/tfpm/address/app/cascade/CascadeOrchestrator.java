package com.jpmc.tfpm.address.app.cascade;

import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.CascadeResult;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.CountryRouter;
import com.jpmc.tfpm.address.domain.EnrichmentError;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.Result;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
    private final long cascadeTimeoutMs;
    private final Set<AddressField> requiredFields;
    private final MeterRegistry meterRegistry;
    private final Map<String, ConfidenceCalibrator> calibrators;
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();

    public CascadeOrchestrator(
            List<AddressStructurer> structurers,
            FieldMerger fieldMerger,
            CountryRouter countryRouter,
            double earlyExitThreshold,
            MeterRegistry meterRegistry) {
        this(structurers, fieldMerger, countryRouter, earlyExitThreshold, 500L, meterRegistry);
    }

    public CascadeOrchestrator(
            List<AddressStructurer> structurers,
            FieldMerger fieldMerger,
            CountryRouter countryRouter,
            double earlyExitThreshold,
            long cascadeTimeoutMs,
            MeterRegistry meterRegistry) {
        this.structurers = List.copyOf(structurers);
        this.fieldMerger = fieldMerger;
        this.countryRouter = countryRouter;
        this.earlyExitThreshold = earlyExitThreshold;
        this.cascadeTimeoutMs = cascadeTimeoutMs;
        this.requiredFields = Set.of(AddressField.CTRY, AddressField.TWN_NM);
        this.meterRegistry = meterRegistry;
        this.calibrators = fieldMerger.calibratorMap();
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
        var deadline = Instant.now().plusMillis(cascadeTimeoutMs);

        for (var structurer : applicableStructurers) {
            if (Instant.now().isAfter(deadline)) {
                LOG.debug("Cascade budget exhausted after {}ms [corrId={}]",
                        cascadeTimeoutMs, correlationId);
                break;
            }

            try {
                var sample = Timer.start(meterRegistry);
                var result = structurer.structure(raw);
                sample.stop(latencyTimer(structurer.name(), raw.countryHint()));

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
            return structurers;
        }
        var allowedSet = Set.copyOf(allowed);
        return structurers.stream()
                .filter(s -> allowedSet.contains(s.name()))
                .toList();
    }

    /**
     * OPT-01: Check only required fields incrementally instead of calling
     * the full fieldMerger.merge() which iterates all 8 AddressFields.
     */
    private boolean shouldEarlyExit(List<StructuringResult> trace, String countryHint) {
        for (var field : requiredFields) {
            double best = -1.0;
            for (var result : trace) {
                var fv = result.fields().get(field);
                if (fv == null || fv.value().isEmpty()) continue;
                var calibrator = calibrators.get(result.structurerName());
                double cal = calibrator != null
                        ? calibrator.calibrate(fv.confidence(), field, countryHint)
                        : Math.max(0.0, Math.min(1.0, fv.confidence()));
                best = Math.max(best, cal);
            }
            if (best < earlyExitThreshold) return false;
        }
        return true;
    }

    private Timer latencyTimer(String structurer, String country) {
        var key = structurer + "|" + country;
        return timerCache.computeIfAbsent(key, k ->
                Timer.builder("address.enrichment.latency")
                        .tag("structurer", structurer)
                        .tag("country", country)
                        .register(meterRegistry));
    }
}

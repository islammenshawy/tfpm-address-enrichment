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
import org.slf4j.MDC;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Runs structurers in parallel for consensus when multiple are configured,
 * or sequentially when only one is available. Automatically enables
 * consensus analysis when 2+ structurers produce results.
 */
@ThreadSafe
public final class CascadeOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(CascadeOrchestrator.class);

    private final List<AddressStructurer> structurers;
    private final FieldMerger fieldMerger;
    private final CountryRouter countryRouter;
    // earlyExitThreshold is configured via enrichment.cascade.early-exit-threshold.
    // Currently unused because all structurers run in parallel. In a future sequential
    // mode, this threshold would allow short-circuiting the cascade when cumulative
    // confidence on all required fields exceeds this value.
    private final double earlyExitThreshold;
    private final long cascadeTimeoutMs;
    private final int minSources;
    private final Set<AddressField> requiredFields;
    private final MeterRegistry meterRegistry;
    private final Map<String, ConfidenceCalibrator> calibrators;
    private final ConsensusAnalyzer consensusAnalyzer;
    private final FieldNormalizer fieldNormalizer;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public CascadeOrchestrator(
            List<AddressStructurer> structurers,
            FieldMerger fieldMerger,
            CountryRouter countryRouter,
            double earlyExitThreshold,
            MeterRegistry meterRegistry) {
        this(structurers, fieldMerger, countryRouter, earlyExitThreshold, 30000L, 2, meterRegistry, Map.of(), 4);
    }

    public CascadeOrchestrator(
            List<AddressStructurer> structurers,
            FieldMerger fieldMerger,
            CountryRouter countryRouter,
            double earlyExitThreshold,
            long cascadeTimeoutMs,
            MeterRegistry meterRegistry) {
        this(structurers, fieldMerger, countryRouter, earlyExitThreshold, cascadeTimeoutMs, 2, meterRegistry, Map.of(), 4);
    }

    public CascadeOrchestrator(
            List<AddressStructurer> structurers,
            FieldMerger fieldMerger,
            CountryRouter countryRouter,
            double earlyExitThreshold,
            long cascadeTimeoutMs,
            MeterRegistry meterRegistry,
            Map<String, Map<String, Double>> consensusWeights) {
        this(structurers, fieldMerger, countryRouter, earlyExitThreshold, cascadeTimeoutMs, 2, meterRegistry, consensusWeights, 4);
    }

    public CascadeOrchestrator(
            List<AddressStructurer> structurers,
            FieldMerger fieldMerger,
            CountryRouter countryRouter,
            double earlyExitThreshold,
            long cascadeTimeoutMs,
            int minSources,
            MeterRegistry meterRegistry,
            Map<String, Map<String, Double>> consensusWeights) {
        this(structurers, fieldMerger, countryRouter, earlyExitThreshold, cascadeTimeoutMs, minSources, meterRegistry, consensusWeights, 4);
    }

    public CascadeOrchestrator(
            List<AddressStructurer> structurers,
            FieldMerger fieldMerger,
            CountryRouter countryRouter,
            double earlyExitThreshold,
            long cascadeTimeoutMs,
            int minSources,
            MeterRegistry meterRegistry,
            Map<String, Map<String, Double>> consensusWeights,
            int parallelThreads) {
        this.structurers = List.copyOf(structurers);
        this.fieldMerger = fieldMerger;
        this.countryRouter = countryRouter;
        this.earlyExitThreshold = earlyExitThreshold;
        this.cascadeTimeoutMs = cascadeTimeoutMs;
        this.minSources = minSources;
        this.requiredFields = Set.of(AddressField.CTRY, AddressField.TWN_NM);
        this.meterRegistry = meterRegistry;
        this.calibrators = fieldMerger.calibratorMap();
        this.consensusAnalyzer = new ConsensusAnalyzer(consensusWeights);
        this.fieldNormalizer = new FieldNormalizer();
        this.executor = Executors.newFixedThreadPool(parallelThreads);
    }

    public Result<CascadeResult> orchestrate(RawAddress raw, String correlationId) {
        var applicableStructurers = filterByCountry(raw.countryHint());
        if (applicableStructurers.isEmpty()) {
            return Result.failure(EnrichmentError.of(
                    EnrichmentError.Category.CASCADE_NO_RESULT,
                    "No structurers available for country hint '" + raw.countryHint() + "'",
                    correlationId));
        }

        List<StructuringResult> trace;
        if (applicableStructurers.size() > 1) {
            trace = runParallel(applicableStructurers, raw, correlationId);
        } else {
            trace = runSingle(applicableStructurers.get(0), raw, correlationId);
        }

        if (trace.isEmpty()) {
            return Result.failure(EnrichmentError.of(
                    EnrichmentError.Category.CASCADE_NO_RESULT,
                    "All structurers failed or returned no results",
                    correlationId));
        }

        // min-sources check: warn if fewer structurers returned results than configured minimum.
        // This does not fail the request — consensus quality may be degraded but a result is still returned.
        long sourcesWithResults = trace.stream().filter(t -> !t.fields().isEmpty()).count();
        if (sourcesWithResults < minSources) {
            LOG.warn("Only {} of {} required min-sources returned results [corrId={}]. "
                            + "Consensus quality may be degraded.",
                    sourcesWithResults, minSources, correlationId);
        }

        // Normalize LLM output to match libpostal's canonical forms.
        // libpostal output is ALREADY canonical — skip normalization for it.
        var normalizedTrace = trace.stream()
                .map(t -> "libpostal".equals(t.structurerName()) ? t : fieldNormalizer.normalize(t))
                .toList();

        var merged = fieldMerger.merge(normalizedTrace, raw.countryHint());
        double confidence = merged.overallConfidence();

        // Consensus: automatic when 2+ structurers produced results
        var activeCount = normalizedTrace.stream().filter(t -> !t.fields().isEmpty()).count();
        var consensus = activeCount >= 2
                ? consensusAnalyzer.analyze(normalizedTrace, merged, raw.countryHint())
                : null;

        if (consensus != null && consensus.hasDisagreements()) {
            LOG.info("Consensus disagreements on {} fields [corrId={}]: {}",
                    consensus.disagreementCount(), correlationId, consensus.flaggedFields());
        }

        return Result.success(new CascadeResult(merged, trace, confidence, consensus));
    }

    /** Run multiple structurers in parallel, wait for all. */
    private List<StructuringResult> runParallel(List<AddressStructurer> structurers,
                                                 RawAddress raw, String correlationId) {
        var results = new ConcurrentLinkedQueue<StructuringResult>();
        var latch = new CountDownLatch(structurers.size());
        var mdcContext = MDC.getCopyOfContextMap();

        for (var structurer : structurers) {
            CompletableFuture.runAsync(() -> {
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                }
                try {
                    var sample = Timer.start(meterRegistry);
                    var result = structurer.structure(raw);
                    sample.stop(latencyTimer(structurer.name(), raw.countryHint()));
                    results.add(result);
                    LOG.debug("Structurer '{}' returned {} fields in {}ms [corrId={}]",
                            structurer.name(), result.fields().size(),
                            result.latency().toMillis(), correlationId);
                } catch (Exception e) {
                    LOG.warn("Structurer '{}' threw unexpectedly [corrId={}]: {}",
                            structurer.name(), correlationId, e.getMessage());
                } finally {
                    MDC.clear();
                    latch.countDown();
                }
            }, executor);
        }

        try {
            latch.await(cascadeTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new ArrayList<>(results);
    }

    /** Run a single structurer (no parallelism needed). */
    private List<StructuringResult> runSingle(AddressStructurer structurer,
                                               RawAddress raw, String correlationId) {
        try {
            var sample = Timer.start(meterRegistry);
            var result = structurer.structure(raw);
            sample.stop(latencyTimer(structurer.name(), raw.countryHint()));
            LOG.debug("Structurer '{}' returned {} fields in {}ms [corrId={}]",
                    structurer.name(), result.fields().size(),
                    result.latency().toMillis(), correlationId);
            return List.of(result);
        } catch (Exception e) {
            LOG.warn("Structurer '{}' threw unexpectedly [corrId={}]: {}",
                    structurer.name(), correlationId, e.getMessage());
            return List.of();
        }
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

    private Timer latencyTimer(String structurer, String country) {
        var key = structurer + "|" + country;
        return timerCache.computeIfAbsent(key, k ->
                Timer.builder("address.enrichment.latency")
                        .tag("structurer", structurer)
                        .tag("country", country)
                        .register(meterRegistry));
    }
}

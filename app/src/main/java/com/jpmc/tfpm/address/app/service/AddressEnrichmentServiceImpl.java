package com.jpmc.tfpm.address.app.service;

import com.jpmc.tfpm.address.app.cascade.CascadeOrchestrator;
import com.jpmc.tfpm.address.domain.CascadeResult;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.AuditLog;
import com.jpmc.tfpm.address.domain.AuditLog.AuditEvent;
import com.jpmc.tfpm.address.domain.ComplianceDecision;
import com.jpmc.tfpm.address.domain.ComplianceRouter;
import com.jpmc.tfpm.address.domain.ComplianceRoutingWriter;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.FieldAttributionWriter;
import com.jpmc.tfpm.address.domain.IdempotencyStore;
import com.jpmc.tfpm.address.domain.IdempotencyStore.ClaimResult;
import com.jpmc.tfpm.address.domain.Result;
import com.jpmc.tfpm.address.domain.ResultPersistence;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single service entry point all three inbound channels converge on.
 * Coordinates idempotency, cascade, persistence, and compliance routing.
 */
@ThreadSafe
public final class AddressEnrichmentServiceImpl implements AddressEnrichmentService {

    private static final Logger LOG = LoggerFactory.getLogger(AddressEnrichmentServiceImpl.class);

    private final IdempotencyStore idempotencyStore;
    private final CascadeOrchestrator cascadeOrchestrator;
    private final ResultPersistence resultPersistence;
    private final ComplianceRouter complianceRouter;
    private final FieldAttributionWriter fieldAttributionWriter;
    private final ComplianceRoutingWriter complianceRoutingWriter;
    private final AuditLog auditLog;
    private final double reviewThreshold;
    private final boolean complianceShadowMode;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public AddressEnrichmentServiceImpl(
            IdempotencyStore idempotencyStore,
            CascadeOrchestrator cascadeOrchestrator,
            ResultPersistence resultPersistence,
            ComplianceRouter complianceRouter,
            FieldAttributionWriter fieldAttributionWriter,
            ComplianceRoutingWriter complianceRoutingWriter,
            AuditLog auditLog,
            double reviewThreshold,
            boolean complianceShadowMode,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.idempotencyStore = idempotencyStore;
        this.cascadeOrchestrator = cascadeOrchestrator;
        this.resultPersistence = resultPersistence;
        this.complianceRouter = complianceRouter;
        this.fieldAttributionWriter = fieldAttributionWriter;
        this.complianceRoutingWriter = complianceRoutingWriter;
        this.auditLog = auditLog;
        this.reviewThreshold = reviewThreshold;
        this.complianceShadowMode = complianceShadowMode;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public EnrichmentResult enrich(EnrichmentRequest request) {
        var correlationId = request.correlationId();
        var sample = Timer.start(meterRegistry);

        // Step 1: Idempotency claim
        var claimResult = idempotencyStore.tryClaim(request);

        if (claimResult.isFailure()) {
            LOG.error("Idempotency claim failed [corrId={}]: {}",
                    correlationId, claimResult.toOptionalError().orElse(null));
            sample.stop(timer("address.enrichment.latency.total",
                    "channel", request.sourceChannel().name(),
                    "outcome", "ERROR"));
            throw new IllegalStateException("Idempotency claim failed: "
                    + claimResult.toOptionalError().map(Object::toString).orElse("unknown"));
        }

        var claim = claimResult.getOrThrow();

        if (!claim.isClaimed()) {
            counter("address.enrichment.idempotency.duplicate",
                    "channel", request.sourceChannel().name()).increment();
            var result = handleDuplicate(claim, correlationId);
            sample.stop(timer("address.enrichment.latency.total",
                    "channel", request.sourceChannel().name(),
                    "outcome", result.outcome().name()));
            return result;
        }

        // Step 2: Run cascade
        var cascadeResult = cascadeOrchestrator.orchestrate(
                request.address(), correlationId);

        var result = switch (cascadeResult) {
            case Result.Success<CascadeResult>(var cr) ->
                    handleCascadeSuccess(request, claim, cr);
            case Result.Failure<CascadeResult> f ->
                    handleCascadeFailure(request, claim);
        };

        counter("address.enrichment.processed",
                "channel", request.sourceChannel().name(),
                "outcome", result.outcome().name()).increment();

        sample.stop(timer("address.enrichment.latency.total",
                "channel", request.sourceChannel().name(),
                "outcome", result.outcome().name()));

        auditLog.record(new AuditEvent(
                "ENRICHMENT_COMPLETED",
                "system",
                "ENRICHMENT_RESULT",
                String.valueOf(result.resultRowId()),
                "{\"outcome\":\"" + result.outcome() + "\",\"correlationId\":\"" + correlationId + "\"}"));

        return result;
    }

    private EnrichmentResult handleDuplicate(ClaimResult claim, String correlationId) {
        LOG.debug("Idempotency duplicate for key={} [corrId={}]",
                claim.idempotencyKey(), correlationId);

        var cachedRowId = idempotencyStore.findCachedResultRowId(claim.idempotencyKey());
        if (cachedRowId.isPresent()) {
            var loadResult = resultPersistence.loadResult(cachedRowId.get(), correlationId);
            if (loadResult instanceof Result.Success<Optional<EnrichmentResult>>(var cached)
                    && cached.isPresent()) {
                return cached.get();
            }
        }

        return new EnrichmentResult(
                correlationId,
                EnrichmentResult.Outcome.PERSISTED_DUPLICATE,
                StructuredAddress.empty(),
                0.0,
                cachedRowId.orElse(null),
                Instant.now());
    }

    private EnrichmentResult handleCascadeSuccess(
            EnrichmentRequest request,
            ClaimResult claim,
            CascadeResult cascadeResult) {

        var correlationId = request.correlationId();
        var address = cascadeResult.structuredAddress();
        var confidence = cascadeResult.overallConfidence();

        // Determine outcome before persisting so persistence stores the
        // service-decided review flag rather than recomputing it independently.
        boolean requiresReview = !address.meetsSr2026Minimum() || confidence < reviewThreshold;

        // Persist result and record idempotency atomically in a single transaction.
        // This prevents inconsistent state if a crash occurs between the two operations.
        long resultRowId = transactionTemplate.execute(status -> {
            var persistResult = resultPersistence.persistResult(request, cascadeResult, requiresReview);
            if (persistResult.isFailure()) {
                LOG.error("Failed to persist result [corrId={}]: {}",
                        correlationId, persistResult.toOptionalError().orElse(null));
                throw new IllegalStateException("Persist failed: "
                        + persistResult.toOptionalError().map(Object::toString).orElse("unknown"));
            }
            long rowId = persistResult.getOrThrow();

            var recordResult = idempotencyStore.recordResult(claim.idempotencyKey(), rowId);
            if (recordResult.isFailure()) {
                LOG.error("Failed to record idempotency [corrId={}]: {}",
                        correlationId, recordResult.toOptionalError().orElse(null));
                throw new IllegalStateException("Idempotency record failed: "
                        + recordResult.toOptionalError().map(Object::toString).orElse("unknown"));
            }

            return rowId;
        });

        // Best-effort: write per-field attributions
        try {
            fieldAttributionWriter.writeAttributions(resultRowId, cascadeResult.structurerTrace(),
                    cascadeResult.structuredAddress(), request.address().countryHint());
        } catch (Exception e) {
            LOG.warn("Failed to write field attributions [corrId={}]: {}", correlationId, e.getMessage(), e);
        }

        // Determine outcome for return value and exception queue
        EnrichmentResult.Outcome outcome;
        if (!address.meetsSr2026Minimum()) {
            outcome = EnrichmentResult.Outcome.REQUIRES_REVIEW;
            resultPersistence.writeToExceptionQueue(resultRowId, "MISSING_REQUIRED");
            counter("address.enrichment.exceptions",
                    "reason", "MISSING_REQUIRED",
                    "country", request.address().countryHint()).increment();
            LOG.info("SR2026 mandatory fields missing [corrId={}]", correlationId);
        } else if (confidence < reviewThreshold) {
            outcome = EnrichmentResult.Outcome.REQUIRES_REVIEW;
            resultPersistence.writeToExceptionQueue(resultRowId, "LOW_CONFIDENCE");
            counter("address.enrichment.exceptions",
                    "reason", "LOW_CONFIDENCE",
                    "country", request.address().countryHint()).increment();
            LOG.info("Confidence {} below threshold {} [corrId={}]",
                    confidence, reviewThreshold, correlationId);
        } else {
            outcome = EnrichmentResult.Outcome.SUCCESS;
        }

        var sources = cascadeResult.structurerTrace().stream()
                .map(t -> t.structurerName())
                .toList();
        var enrichmentResult = new EnrichmentResult(
                correlationId, outcome, address, confidence, resultRowId, Instant.now(),
                sources, cascadeResult.consensus());

        evaluateCompliance(enrichmentResult, request);

        return enrichmentResult;
    }

    private EnrichmentResult handleCascadeFailure(
            EnrichmentRequest request,
            ClaimResult claim) {

        var correlationId = request.correlationId();
        LOG.warn("Cascade produced no usable fields [corrId={}]", correlationId);

        var emptyResult = new CascadeResult(
                StructuredAddress.empty(), java.util.List.of(), 0.0);

        // Persist result and record idempotency atomically in a single transaction.
        long resultRowId = transactionTemplate.execute(status -> {
            var persistResult = resultPersistence.persistResult(request, emptyResult, true);
            if (persistResult.isFailure()) {
                LOG.error("Failed to persist empty result [corrId={}]: {}",
                        correlationId, persistResult.toOptionalError().orElse(null));
                throw new IllegalStateException("Persist failed: "
                        + persistResult.toOptionalError().map(Object::toString).orElse("unknown"));
            }
            long rowId = persistResult.getOrThrow();

            var recordResult = idempotencyStore.recordResult(claim.idempotencyKey(), rowId);
            if (recordResult.isFailure()) {
                LOG.error("Failed to record idempotency [corrId={}]: {}",
                        correlationId, recordResult.toOptionalError().orElse(null));
                throw new IllegalStateException("Idempotency record failed: "
                        + recordResult.toOptionalError().map(Object::toString).orElse("unknown"));
            }

            return rowId;
        });

        resultPersistence.writeToExceptionQueue(resultRowId, "UNSTRUCTURABLE");

        counter("address.enrichment.exceptions",
                "reason", "UNSTRUCTURABLE",
                "country", request.address().countryHint()).increment();

        return new EnrichmentResult(
                correlationId,
                EnrichmentResult.Outcome.UNSTRUCTURABLE,
                StructuredAddress.empty(),
                0.0,
                resultRowId,
                Instant.now());
    }

    private void evaluateCompliance(EnrichmentResult result, EnrichmentRequest request) {
        try {
            var decision = complianceRouter.evaluate(result, request);
            switch (decision) {
                case ComplianceDecision.Bypass b ->
                        LOG.debug("Compliance bypass [corrId={}]", request.correlationId());
                case ComplianceDecision.RouteToCompliance r -> {
                    LOG.info("Compliance route: {} [corrId={}]",
                            r.primaryReason(), request.correlationId());
                    if (!complianceShadowMode) {
                        LOG.warn("Non-shadow compliance dispatch is not yet implemented [corrId={}]",
                                request.correlationId());
                    }
                }
                case ComplianceDecision.Block b -> {
                    LOG.warn("Compliance block: {} [corrId={}]",
                            b.reason(), request.correlationId());
                    if (!complianceShadowMode) {
                        LOG.warn("Non-shadow compliance dispatch is not yet implemented [corrId={}]",
                                request.correlationId());
                    }
                }
            }

            // Best-effort: persist the compliance routing decision
            try {
                complianceRoutingWriter.record(result.resultRowId(),
                        request.address().countryHint(), decision, request.correlationId());
            } catch (Exception e) {
                LOG.warn("Failed to write compliance routing decision [corrId={}]: {}",
                        request.correlationId(), e.getMessage(), e);
            }
        } catch (Exception e) {
            LOG.error("Compliance evaluation failed [corrId={}]", request.correlationId(), e);
        }
    }

    private Counter counter(String name, String... tags) {
        var key = name + "|" + String.join("|", tags);
        return counterCache.computeIfAbsent(key, k ->
                Counter.builder(name).tags(tags).register(meterRegistry));
    }

    private Timer timer(String name, String... tags) {
        var key = name + "|" + String.join("|", tags);
        return timerCache.computeIfAbsent(key, k ->
                Timer.builder(name).tags(tags).register(meterRegistry));
    }
}

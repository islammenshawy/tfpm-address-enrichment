package com.jpmc.tfpm.address.app.service;

import com.jpmc.tfpm.address.app.cascade.CascadeOrchestrator;
import com.jpmc.tfpm.address.domain.CascadeResult;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.ComplianceDecision;
import com.jpmc.tfpm.address.domain.ComplianceRouter;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
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

import java.time.Instant;

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
    private final double reviewThreshold;
    private final MeterRegistry meterRegistry;

    public AddressEnrichmentServiceImpl(
            IdempotencyStore idempotencyStore,
            CascadeOrchestrator cascadeOrchestrator,
            ResultPersistence resultPersistence,
            ComplianceRouter complianceRouter,
            double reviewThreshold,
            MeterRegistry meterRegistry) {
        this.idempotencyStore = idempotencyStore;
        this.cascadeOrchestrator = cascadeOrchestrator;
        this.resultPersistence = resultPersistence;
        this.complianceRouter = complianceRouter;
        this.reviewThreshold = reviewThreshold;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public EnrichmentResult enrich(EnrichmentRequest request) {
        var correlationId = request.correlationId();
        var sample = Timer.start(meterRegistry);

        // Step 1: Idempotency claim
        var claim = idempotencyStore.tryClaim(request);

        if (!claim.isClaimed()) {
            Counter.builder("address.enrichment.idempotency.duplicate")
                    .tag("channel", request.sourceChannel().name())
                    .register(meterRegistry).increment();
            var result = handleDuplicate(claim, correlationId);
            sample.stop(Timer.builder("address.enrichment.latency.total")
                    .tag("channel", request.sourceChannel().name())
                    .tag("outcome", result.outcome().name())
                    .register(meterRegistry));
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

        Counter.builder("address.enrichment.processed")
                .tag("channel", request.sourceChannel().name())
                .tag("outcome", result.outcome().name())
                .register(meterRegistry).increment();

        sample.stop(Timer.builder("address.enrichment.latency.total")
                .tag("channel", request.sourceChannel().name())
                .tag("outcome", result.outcome().name())
                .register(meterRegistry));

        return result;
    }

    private EnrichmentResult handleDuplicate(ClaimResult claim, String correlationId) {
        LOG.debug("Idempotency duplicate for key={} [corrId={}]",
                claim.idempotencyKey(), correlationId);

        var cachedRowId = idempotencyStore.findCachedResultRowId(claim.idempotencyKey());
        if (cachedRowId.isPresent()) {
            var cached = resultPersistence.loadResult(cachedRowId.get(), correlationId);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        // Cached result not yet visible — return a duplicate marker
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

        // Persist result
        long resultRowId = resultPersistence.persistResult(request, cascadeResult);
        idempotencyStore.recordResult(claim.idempotencyKey(), resultRowId);

        // Determine outcome
        EnrichmentResult.Outcome outcome;
        if (!address.meetsSr2026Minimum()) {
            outcome = EnrichmentResult.Outcome.REQUIRES_REVIEW;
            resultPersistence.writeToExceptionQueue(resultRowId, "MISSING_REQUIRED");
            Counter.builder("address.enrichment.exceptions")
                    .tag("reason", "MISSING_REQUIRED")
                    .tag("country", request.address().countryHint())
                    .register(meterRegistry).increment();
            LOG.info("SR2026 mandatory fields missing [corrId={}]", correlationId);
        } else if (confidence < reviewThreshold) {
            outcome = EnrichmentResult.Outcome.REQUIRES_REVIEW;
            resultPersistence.writeToExceptionQueue(resultRowId, "LOW_CONFIDENCE");
            Counter.builder("address.enrichment.exceptions")
                    .tag("reason", "LOW_CONFIDENCE")
                    .tag("country", request.address().countryHint())
                    .register(meterRegistry).increment();
            LOG.info("Confidence {:.2f} below threshold {:.2f} [corrId={}]",
                    confidence, reviewThreshold, correlationId);
        } else {
            outcome = EnrichmentResult.Outcome.SUCCESS;
        }

        var enrichmentResult = new EnrichmentResult(
                correlationId, outcome, address, confidence, resultRowId, Instant.now());

        // Compliance routing (fire-and-forget in shadow mode)
        evaluateCompliance(enrichmentResult, request);

        return enrichmentResult;
    }

    private EnrichmentResult handleCascadeFailure(
            EnrichmentRequest request,
            ClaimResult claim) {

        var correlationId = request.correlationId();
        LOG.warn("Cascade produced no usable fields [corrId={}]", correlationId);

        // Persist empty result for audit trail
        var emptyResult = new CascadeResult(
                StructuredAddress.empty(), java.util.List.of(), 0.0);
        long resultRowId = resultPersistence.persistResult(request, emptyResult);
        idempotencyStore.recordResult(claim.idempotencyKey(), resultRowId);
        resultPersistence.writeToExceptionQueue(resultRowId, "UNSTRUCTURABLE");

        Counter.builder("address.enrichment.exceptions")
                .tag("reason", "UNSTRUCTURABLE")
                .tag("country", request.address().countryHint())
                .register(meterRegistry).increment();

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
                case ComplianceDecision.RouteToCompliance r ->
                        LOG.info("Compliance route: {} [corrId={}]",
                                r.primaryReason(), request.correlationId());
                case ComplianceDecision.Block b ->
                        LOG.warn("Compliance block: {} [corrId={}]",
                                b.reason(), request.correlationId());
            }
        } catch (Exception e) {
            LOG.error("Compliance evaluation failed [corrId={}]", request.correlationId(), e);
        }
    }
}

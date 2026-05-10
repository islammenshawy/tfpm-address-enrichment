package com.jpmc.tfpm.address.app.service;

import com.jpmc.tfpm.address.app.cascade.CascadeOrchestrator;
import com.jpmc.tfpm.address.domain.CascadeResult;
import com.jpmc.tfpm.address.app.cascade.FieldMerger;
import com.jpmc.tfpm.address.app.cascade.IdentityConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.*;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.IdempotencyStore.ClaimResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("AddressEnrichmentServiceImpl")
class AddressEnrichmentServiceImplTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private IdempotencyStore idempotencyStore;
    private ResultPersistence resultPersistence;
    private AddressEnrichmentServiceImpl service;

    private static final EnrichmentRequest REQUEST = new EnrichmentRequest(
            "corr-1", EnrichmentRequest.SourceChannel.HTTP, RawAddress.of("123 Main St"));

    private CascadeOrchestrator makeOrchestrator(Map<AddressField, FieldValue> fields) {
        AddressStructurer stub = new AddressStructurer() {
            @Override public String name() { return "stub"; }
            @Override public Set<AddressField> supportedFields() { return EnumSet.allOf(AddressField.class); }
            @Override public StructuringResult structure(RawAddress raw) {
                return new StructuringResult("stub", fields, Duration.ofMillis(1), Map.of());
            }
        };
        var calibrators = List.<ConfidenceCalibrator>of(new IdentityConfidenceCalibrator("stub"));
        var merger = new FieldMerger(calibrators);
        return new CascadeOrchestrator(List.of(stub), merger, CountryRouter.noOp(), 0.92, meterRegistry);
    }

    private CascadeOrchestrator makeFailingOrchestrator() {
        AddressStructurer failing = new AddressStructurer() {
            @Override public String name() { return "failing"; }
            @Override public Set<AddressField> supportedFields() { return EnumSet.allOf(AddressField.class); }
            @Override public StructuringResult structure(RawAddress raw) {
                throw new RuntimeException("sidecar down");
            }
        };
        var calibrators = List.<ConfidenceCalibrator>of(new IdentityConfidenceCalibrator("failing"));
        var merger = new FieldMerger(calibrators);
        return new CascadeOrchestrator(List.of(failing), merger, CountryRouter.noOp(), 0.92, meterRegistry);
    }

    @BeforeEach
    void setUp() {
        idempotencyStore = mock(IdempotencyStore.class);
        resultPersistence = mock(ResultPersistence.class);
    }

    private AddressEnrichmentServiceImpl makeService(CascadeOrchestrator orchestrator) {
        return new AddressEnrichmentServiceImpl(
                idempotencyStore,
                orchestrator,
                resultPersistence,
                ComplianceRouter.alwaysBypass(),
                0.70,
                meterRegistry);
    }

    @Test
    void claimed_and_successful_cascade_returns_success() {
        var orchestrator = makeOrchestrator(Map.of(
                AddressField.CTRY, new FieldValue("US", 0.95),
                AddressField.TWN_NM, new FieldValue("New York", 0.90)));

        service = makeService(orchestrator);

        when(idempotencyStore.tryClaim(REQUEST))
                .thenReturn(ClaimResult.claimed("key-1"));
        when(resultPersistence.persistResult(eq(REQUEST), any(CascadeResult.class)))
                .thenReturn(42L);

        var result = service.enrich(REQUEST);

        assertThat(result.outcome()).isEqualTo(EnrichmentResult.Outcome.SUCCESS);
        assertThat(result.resultRowId()).isEqualTo(42L);
        verify(idempotencyStore).recordResult("key-1", 42L);
        verify(resultPersistence, never()).writeToExceptionQueue(anyLong(), anyString());
    }

    @Test
    void claimed_but_low_confidence_returns_requires_review() {
        var orchestrator = makeOrchestrator(Map.of(
                AddressField.CTRY, new FieldValue("AE", 0.60),
                AddressField.TWN_NM, new FieldValue("Dubai", 0.55)));

        service = makeService(orchestrator);

        when(idempotencyStore.tryClaim(REQUEST))
                .thenReturn(ClaimResult.claimed("key-2"));
        when(resultPersistence.persistResult(eq(REQUEST), any(CascadeResult.class)))
                .thenReturn(43L);

        var result = service.enrich(REQUEST);

        assertThat(result.outcome()).isEqualTo(EnrichmentResult.Outcome.REQUIRES_REVIEW);
        verify(resultPersistence).writeToExceptionQueue(43L, "LOW_CONFIDENCE");
    }

    @Test
    void claimed_but_missing_required_fields_returns_requires_review() {
        // Only CTRY, missing TWN_NM
        var orchestrator = makeOrchestrator(Map.of(
                AddressField.CTRY, new FieldValue("US", 0.99)));

        service = makeService(orchestrator);

        when(idempotencyStore.tryClaim(REQUEST))
                .thenReturn(ClaimResult.claimed("key-3"));
        when(resultPersistence.persistResult(eq(REQUEST), any(CascadeResult.class)))
                .thenReturn(44L);

        var result = service.enrich(REQUEST);

        assertThat(result.outcome()).isEqualTo(EnrichmentResult.Outcome.REQUIRES_REVIEW);
        verify(resultPersistence).writeToExceptionQueue(44L, "MISSING_REQUIRED");
    }

    @Test
    void cascade_failure_returns_unstructurable() {
        var orchestrator = makeFailingOrchestrator();
        service = makeService(orchestrator);

        when(idempotencyStore.tryClaim(REQUEST))
                .thenReturn(ClaimResult.claimed("key-4"));
        when(resultPersistence.persistResult(eq(REQUEST), any(CascadeResult.class)))
                .thenReturn(45L);

        var result = service.enrich(REQUEST);

        assertThat(result.outcome()).isEqualTo(EnrichmentResult.Outcome.UNSTRUCTURABLE);
        verify(resultPersistence).writeToExceptionQueue(45L, "UNSTRUCTURABLE");
    }

    @Test
    void duplicate_claim_returns_persisted_duplicate() {
        var orchestrator = makeOrchestrator(Map.of()); // won't be called
        service = makeService(orchestrator);

        when(idempotencyStore.tryClaim(REQUEST))
                .thenReturn(ClaimResult.duplicate("key-5"));
        when(idempotencyStore.findCachedResultRowId("key-5"))
                .thenReturn(Optional.of(99L));

        var cachedResult = new EnrichmentResult(
                "corr-1", EnrichmentResult.Outcome.SUCCESS,
                StructuredAddress.empty(), 0.95, 99L, Instant.now());
        when(resultPersistence.loadResult(99L, "corr-1"))
                .thenReturn(Optional.of(cachedResult));

        var result = service.enrich(REQUEST);

        assertThat(result.outcome()).isEqualTo(EnrichmentResult.Outcome.SUCCESS);
        assertThat(result.resultRowId()).isEqualTo(99L);
    }

    @Test
    void duplicate_claim_with_no_cached_result_returns_duplicate_marker() {
        var orchestrator = makeOrchestrator(Map.of());
        service = makeService(orchestrator);

        when(idempotencyStore.tryClaim(REQUEST))
                .thenReturn(ClaimResult.duplicate("key-6"));
        when(idempotencyStore.findCachedResultRowId("key-6"))
                .thenReturn(Optional.empty());

        var result = service.enrich(REQUEST);

        assertThat(result.outcome()).isEqualTo(EnrichmentResult.Outcome.PERSISTED_DUPLICATE);
    }
}

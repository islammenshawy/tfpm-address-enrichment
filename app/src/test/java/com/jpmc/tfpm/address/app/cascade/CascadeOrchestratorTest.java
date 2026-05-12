package com.jpmc.tfpm.address.app.cascade;

import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.CascadeResult;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.CountryRouter;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CascadeOrchestrator")
class CascadeOrchestratorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static AddressStructurer stubStructurer(String name, Map<AddressField, FieldValue> fields) {
        return new AddressStructurer() {
            @Override public String name() { return name; }
            @Override public Set<AddressField> supportedFields() { return EnumSet.allOf(AddressField.class); }
            @Override public StructuringResult structure(RawAddress raw) {
                return new StructuringResult(name, fields, Duration.ofMillis(5), Map.of());
            }
        };
    }

    private static AddressStructurer failingStructurer(String name) {
        return new AddressStructurer() {
            @Override public String name() { return name; }
            @Override public Set<AddressField> supportedFields() { return EnumSet.allOf(AddressField.class); }
            @Override public StructuringResult structure(RawAddress raw) {
                throw new RuntimeException("sidecar timeout");
            }
        };
    }

    private CascadeOrchestrator makeOrchestrator(List<AddressStructurer> structurers) {
        return makeOrchestrator(structurers, CountryRouter.noOp(), 0.92);
    }

    private CascadeOrchestrator makeOrchestrator(
            List<AddressStructurer> structurers,
            CountryRouter router,
            double earlyExitThreshold) {
        var calibrators = structurers.stream()
                .map(s -> (com.jpmc.tfpm.address.domain.ConfidenceCalibrator)
                        new IdentityConfidenceCalibrator(s.name()))
                .toList();
        var merger = new FieldMerger(calibrators);
        return new CascadeOrchestrator(structurers, merger, router, earlyExitThreshold, meterRegistry);
    }

    @Test
    void happy_path_all_structurers_run() {
        var s1 = stubStructurer("libpostal", Map.of(
                AddressField.STRT_NM, new FieldValue("Main St", 0.90)));
        var s2 = stubStructurer("llm", Map.of(
                AddressField.CTRY, new FieldValue("US", 0.85),
                AddressField.TWN_NM, new FieldValue("NYC", 0.80)));

        var orchestrator = makeOrchestrator(List.of(s1, s2));
        var result = orchestrator.orchestrate(RawAddress.of("123 Main St, NYC"), "corr-1");

        assertThat(result.isSuccess()).isTrue();
        var cascade = ((Result.Success<CascadeResult>) result).value();
        assertThat(cascade.structurerTrace()).hasSize(2);
        assertThat(cascade.structuredAddress().fields()).hasSize(3);
    }

    @Test
    void early_exit_when_threshold_met() {
        var callCount = new AtomicInteger(0);
        var s1 = new AddressStructurer() {
            @Override public String name() { return "fast"; }
            @Override public Set<AddressField> supportedFields() { return EnumSet.allOf(AddressField.class); }
            @Override public StructuringResult structure(RawAddress raw) {
                callCount.incrementAndGet();
                return new StructuringResult("fast", Map.of(
                        AddressField.CTRY, new FieldValue("US", 0.99),
                        AddressField.TWN_NM, new FieldValue("NYC", 0.99)),
                        Duration.ZERO, Map.of());
            }
        };
        var s2 = new AddressStructurer() {
            @Override public String name() { return "slow"; }
            @Override public Set<AddressField> supportedFields() { return EnumSet.allOf(AddressField.class); }
            @Override public StructuringResult structure(RawAddress raw) {
                callCount.incrementAndGet();
                return StructuringResult.empty("slow", Duration.ZERO);
            }
        };

        var orchestrator = makeOrchestrator(List.of(s1, s2));
        var result = orchestrator.orchestrate(RawAddress.of("test"), "corr-2");

        assertThat(result.isSuccess()).isTrue();
        // s2 should NOT be called because s1 already met threshold
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void country_router_filters_structurers() {
        var s1 = stubStructurer("libpostal", Map.of(
                AddressField.CTRY, new FieldValue("CN", 0.30)));
        var s2 = stubStructurer("llm", Map.of(
                AddressField.CTRY, new FieldValue("CN", 0.90),
                AddressField.TWN_NM, new FieldValue("Shanghai", 0.85)));

        // Router says: for CN, only use llm (skip libpostal)
        CountryRouter router = hint -> hint.equals("CN") ? List.of("llm") : List.of();

        var orchestrator = makeOrchestrator(List.of(s1, s2), router, 0.92);
        var result = orchestrator.orchestrate(new RawAddress("addr", "CN", ""), "corr-3");

        assertThat(result.isSuccess()).isTrue();
        var cascade = ((Result.Success<CascadeResult>) result).value();
        assertThat(cascade.structurerTrace()).hasSize(1);
        assertThat(cascade.structurerTrace().get(0).structurerName()).isEqualTo("llm");
    }

    @Test
    void failing_structurer_is_skipped() {
        var s1 = failingStructurer("broken");
        var s2 = stubStructurer("llm", Map.of(
                AddressField.CTRY, new FieldValue("US", 0.90)));

        var orchestrator = makeOrchestrator(List.of(s1, s2));
        var result = orchestrator.orchestrate(RawAddress.of("test"), "corr-4");

        assertThat(result.isSuccess()).isTrue();
        var cascade = ((Result.Success<CascadeResult>) result).value();
        assertThat(cascade.structurerTrace()).hasSize(1);
        assertThat(cascade.structurerTrace().get(0).structurerName()).isEqualTo("llm");
    }

    @Test
    void all_structurers_fail_returns_cascade_no_result() {
        var s1 = failingStructurer("broken1");
        var s2 = failingStructurer("broken2");

        var orchestrator = makeOrchestrator(List.of(s1, s2));
        var result = orchestrator.orchestrate(RawAddress.of("test"), "corr-5");

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void concurrency_100_threads_x_1000_calls_no_corruption() throws Exception {
        var s1 = stubStructurer("stub", Map.of(
                AddressField.CTRY, new FieldValue("US", 0.95),
                AddressField.TWN_NM, new FieldValue("NYC", 0.90)));

        var orchestrator = makeOrchestrator(List.of(s1));

        // Baseline: single-threaded result for comparison
        var baseline = orchestrator.orchestrate(RawAddress.of("baseline"), "baseline");
        assertThat(baseline.isSuccess()).isTrue();
        var baselineFields = ((Result.Success<CascadeResult>) baseline).value()
                .structuredAddress().fields().size();

        // Concurrent: 100 threads x 1000 calls = 100,000 total
        int threads = 100;
        int callsPerThread = 1000;
        var executor = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(threads);
        var errors = new AtomicInteger(0);
        var fieldCountMismatches = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            executor.submit(() -> {
                try {
                    for (int c = 0; c < callsPerThread; c++) {
                        var r = orchestrator.orchestrate(
                                RawAddress.of("addr-" + threadIdx + "-" + c),
                                "corr-" + threadIdx + "-" + c);
                        if (!r.isSuccess()) {
                            errors.incrementAndGet();
                        } else {
                            var fields = ((Result.Success<CascadeResult>) r).value()
                                    .structuredAddress().fields().size();
                            if (fields != baselineFields) fieldCountMismatches.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        assertThat(errors.get()).as("No errors across 100K concurrent calls").isZero();
        assertThat(fieldCountMismatches.get())
                .as("All results match single-threaded baseline field count").isZero();
    }
}

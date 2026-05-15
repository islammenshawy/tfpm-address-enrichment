package com.jpmc.tfpm.address.app.config;

import com.jpmc.tfpm.address.app.accuracy.AccuracySamplingJob;
import com.jpmc.tfpm.address.app.backfill.LegacyBackfillJob;
import com.jpmc.tfpm.address.app.cascade.CascadeOrchestrator;
import com.jpmc.tfpm.address.app.cascade.FieldMerger;
import com.jpmc.tfpm.address.app.cascade.IdentityConfidenceCalibrator;
import com.jpmc.tfpm.address.app.compliance.ComplianceProperties;
import com.jpmc.tfpm.address.app.compliance.FourAxisComplianceRouter;
import com.jpmc.tfpm.address.app.routing.ConfigDrivenCountryRouter;
import com.jpmc.tfpm.address.app.service.AddressEnrichmentServiceImpl;
import com.jpmc.tfpm.address.domain.AccuracySampler;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.AuditLog;
import com.jpmc.tfpm.address.domain.ComplianceRouter;
import com.jpmc.tfpm.address.domain.ComplianceRoutingWriter;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.CountryRouter;
import com.jpmc.tfpm.address.domain.FieldAttributionWriter;
import com.jpmc.tfpm.address.domain.IdempotencyStore;
import com.jpmc.tfpm.address.domain.LegacyAddressCursor;
import com.jpmc.tfpm.address.domain.ResultPersistence;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

/**
 * Core service bean wiring. All pipeline components are registered here
 * via @Bean methods, accepting domain interfaces as parameters so Spring
 * auto-wires by type. This keeps the app module decoupled from concrete
 * adapter implementations per CLAUDE.md.
 */
@Configuration
@EnableConfigurationProperties(ComplianceProperties.class)
public class ServiceConfig {

    @Bean
    public ConfidenceCalibrator identityConfidenceCalibrator() {
        return new IdentityConfidenceCalibrator("stub");
    }

    @Bean
    public FieldMerger fieldMerger(List<ConfidenceCalibrator> calibrators) {
        return new FieldMerger(calibrators);
    }

    @Bean
    public CountryRouter configDrivenCountryRouter(
            @Value("#{${enrichment.cascade.routing:{}}}") Map<String, List<String>> routing) {
        return new ConfigDrivenCountryRouter(routing != null ? routing : Map.of());
    }

    @Bean
    public CascadeOrchestrator cascadeOrchestrator(
            List<AddressStructurer> structurers,
            FieldMerger fieldMerger,
            CountryRouter countryRouter,
            @Value("${enrichment.cascade.early-exit-threshold:0.92}") double earlyExitThreshold,
            @Value("${enrichment.cascade.timeout-ms:500}") long cascadeTimeoutMs,
            @Value("${enrichment.cascade.min-sources:2}") int minSources,
            MeterRegistry meterRegistry,
            @Value("#{${enrichment.consensus.source-weights:{}}}") Map<String, Map<String, Double>> consensusWeights,
            @Value("${enrichment.cascade.parallel-threads:4}") int parallelThreads) {
        return new CascadeOrchestrator(
                structurers, fieldMerger, countryRouter,
                earlyExitThreshold, cascadeTimeoutMs, minSources, meterRegistry,
                consensusWeights != null ? consensusWeights : Map.of(),
                parallelThreads);
    }

    @Bean
    public ComplianceRouter complianceRouter(ComplianceProperties props) {
        if (!props.enabled()) {
            return ComplianceRouter.alwaysBypass();
        }
        return new FourAxisComplianceRouter(props);
    }

    @Bean
    public AddressEnrichmentService addressEnrichmentService(
            IdempotencyStore idempotencyStore,
            CascadeOrchestrator cascadeOrchestrator,
            ResultPersistence resultPersistence,
            ComplianceRouter complianceRouter,
            FieldAttributionWriter fieldAttributionWriter,
            ComplianceRoutingWriter complianceRoutingWriter,
            AuditLog auditLog,
            @Value("${enrichment.review-threshold:0.70}") double reviewThreshold,
            ComplianceProperties complianceProperties,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager) {
        return new AddressEnrichmentServiceImpl(
                idempotencyStore, cascadeOrchestrator, resultPersistence,
                complianceRouter, fieldAttributionWriter, complianceRoutingWriter,
                auditLog, reviewThreshold, complianceProperties.shadowMode(),
                meterRegistry, transactionManager);
    }

    @Bean
    @ConditionalOnProperty(name = "enrichment.accuracy.sampling.enabled", havingValue = "true", matchIfMissing = false)
    public AccuracySamplingJob accuracySamplingJob(AccuracySampler sampler) {
        return new AccuracySamplingJob(sampler);
    }

    @Bean
    @ConditionalOnProperty(name = "enrichment.backfill.enabled", havingValue = "true", matchIfMissing = false)
    public LegacyBackfillJob legacyBackfillJob(
            LegacyAddressCursor cursor,
            AddressEnrichmentService enrichmentService) {
        return new LegacyBackfillJob(cursor, enrichmentService);
    }
}

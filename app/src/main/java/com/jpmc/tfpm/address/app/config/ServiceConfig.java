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
import com.jpmc.tfpm.address.domain.*;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
            MeterRegistry meterRegistry,
            @Value("#{${enrichment.consensus.source-weights:{}}}") Map<String, Map<String, Double>> consensusWeights) {
        return new CascadeOrchestrator(
                structurers, fieldMerger, countryRouter,
                earlyExitThreshold, cascadeTimeoutMs, meterRegistry,
                consensusWeights != null ? consensusWeights : Map.of());
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
            AuditLog auditLog,
            @Value("${enrichment.review-threshold:0.70}") double reviewThreshold,
            MeterRegistry meterRegistry) {
        return new AddressEnrichmentServiceImpl(
                idempotencyStore, cascadeOrchestrator, resultPersistence,
                complianceRouter, auditLog, reviewThreshold, meterRegistry);
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

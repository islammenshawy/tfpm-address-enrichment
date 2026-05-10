package com.jpmc.tfpm.address.app.compliance;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.ComplianceDecision;
import com.jpmc.tfpm.address.domain.ComplianceRouter;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Four-axis compliance routing decision:
 * <ol>
 *   <li>Per-field confidence floors (CTRY: 0.95, TWN_NM: 0.80, etc.)</li>
 *   <li>Overall confidence floor (0.85 default)</li>
 *   <li>Country risk tiers (OFAC/FATF high-risk countries)</li>
 *   <li>Pattern triggers (sanctions watch patterns in raw address text)</li>
 * </ol>
 */
@ThreadSafe
public final class FourAxisComplianceRouter implements ComplianceRouter {

    private static final Logger LOG = LoggerFactory.getLogger(FourAxisComplianceRouter.class);

    private final Map<AddressField, Double> fieldConfidenceFloors;
    private final double overallConfidenceFloor;
    private final Set<String> highRiskCountries;
    private final List<Pattern> sanctionsPatterns;
    private final boolean shadowMode;
    private final String failSafeAction;

    public FourAxisComplianceRouter(ComplianceProperties props) {
        this.overallConfidenceFloor = props.overallConfidenceFloor();
        this.shadowMode = props.shadowMode();
        this.failSafeAction = props.failSafeAction();

        var floors = new EnumMap<AddressField, Double>(AddressField.class);
        for (var entry : props.fieldConfidenceFloor().entrySet()) {
            try {
                floors.put(AddressField.valueOf(entry.getKey()), entry.getValue());
            } catch (IllegalArgumentException ignored) {
                LOG.warn("Unknown field in compliance floor config: {}", entry.getKey());
            }
        }
        this.fieldConfidenceFloors = Collections.unmodifiableMap(floors);
        this.highRiskCountries = loadCsvSet(props.highRiskCountriesResource());
        this.sanctionsPatterns = loadPatterns(props.patternTriggersResource());
    }

    @Override
    public ComplianceDecision evaluate(EnrichmentResult result, EnrichmentRequest request) {
        try {
            var reasons = EnumSet.noneOf(ComplianceReason.class);

            // Axis 1: Per-field confidence
            if (!result.structuredAddress().fields().isEmpty()) {
                for (var entry : fieldConfidenceFloors.entrySet()) {
                    var fv = result.structuredAddress().get(entry.getKey());
                    if (fv.isEmpty() || fv.get().confidence() < entry.getValue()) {
                        reasons.add(ComplianceReason.LOW_FIELD_CONFIDENCE);
                        break;
                    }
                }
            }

            // Axis 2: Overall confidence
            if (result.overallConfidence() < overallConfidenceFloor) {
                reasons.add(ComplianceReason.LOW_OVERALL_CONFIDENCE);
            }

            // Axis 3: Country risk
            var country = request.address().countryHint().toUpperCase();
            if (!country.isEmpty() && highRiskCountries.contains(country)) {
                reasons.add(ComplianceReason.HIGH_RISK_COUNTRY);
            }

            // Axis 4: Pattern triggers
            var rawText = request.address().raw().toUpperCase();
            for (var pattern : sanctionsPatterns) {
                if (pattern.matcher(rawText).find()) {
                    reasons.add(ComplianceReason.SANCTIONS_PATTERN_MATCH);
                    break;
                }
            }

            // Schema incomplete
            if (!result.structuredAddress().meetsSr2026Minimum()) {
                reasons.add(ComplianceReason.SCHEMA_INCOMPLETE);
            }

            // Unstructurable
            if (result.outcome() == EnrichmentResult.Outcome.UNSTRUCTURABLE) {
                reasons.add(ComplianceReason.UNSTRUCTURABLE);
            }

            if (reasons.isEmpty()) {
                return new ComplianceDecision.Bypass();
            }

            // Determine urgency
            var urgency = reasons.contains(ComplianceReason.SANCTIONS_PATTERN_MATCH)
                    || reasons.contains(ComplianceReason.HIGH_RISK_COUNTRY)
                    ? "EXPEDITED" : "STANDARD";

            var primary = reasons.iterator().next();
            var decision = new ComplianceDecision.RouteToCompliance(primary, reasons, urgency);

            if (shadowMode) {
                LOG.info("SHADOW compliance decision: {} reasons={} [corrId={}]",
                        decision, reasons, request.correlationId());
                return new ComplianceDecision.Bypass();
            }

            return decision;

        } catch (Exception e) {
            LOG.error("Compliance evaluation error [corrId={}]", request.correlationId(), e);
            if ("CONSERVATIVE".equals(failSafeAction)) {
                return new ComplianceDecision.RouteToCompliance(
                        ComplianceReason.UNSTRUCTURABLE,
                        Set.of(ComplianceReason.UNSTRUCTURABLE),
                        "BLOCKING");
            }
            return new ComplianceDecision.Bypass();
        }
    }

    private static Set<String> loadCsvSet(String resource) {
        if (resource == null || resource.isBlank()) return Set.of();
        try {
            var res = new DefaultResourceLoader().getResource(resource);
            if (!res.exists()) return Set.of();
            try (var reader = new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
                var set = new HashSet<String>();
                String line;
                while ((line = reader.readLine()) != null) {
                    var trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        set.add(trimmed.toUpperCase());
                    }
                }
                return Collections.unmodifiableSet(set);
            }
        } catch (IOException e) {
            LOG.warn("Failed to load resource {}: {}", resource, e.getMessage());
            return Set.of();
        }
    }

    private static List<Pattern> loadPatterns(String resource) {
        if (resource == null || resource.isBlank()) return List.of();
        try {
            var res = new DefaultResourceLoader().getResource(resource);
            if (!res.exists()) return List.of();
            try (var reader = new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
                var patterns = new ArrayList<Pattern>();
                String line;
                while ((line = reader.readLine()) != null) {
                    var trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        patterns.add(Pattern.compile(trimmed, Pattern.CASE_INSENSITIVE));
                    }
                }
                return Collections.unmodifiableList(patterns);
            }
        } catch (IOException e) {
            LOG.warn("Failed to load patterns from {}: {}", resource, e.getMessage());
            return List.of();
        }
    }
}

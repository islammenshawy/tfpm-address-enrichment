package com.jpmc.tfpm.address.app.review;

import com.jpmc.tfpm.address.app.config.ReviewRulesProperties;
import com.jpmc.tfpm.address.app.config.ReviewRulesProperties.ReviewRule;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService.ReviewReason;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.ConsensusResult;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evaluates configurable review rules against an enrichment result.
 * Each rule type checks conditions and returns a {@link ReviewReason} if triggered.
 *
 * <p>All fields are final; this class is safe for concurrent use.
 */
@ThreadSafe
public final class ReviewRulesEngine {

    private final List<ReviewRule> enabledRules;

    public ReviewRulesEngine(ReviewRulesProperties properties) {
        this.enabledRules = properties.rules().stream()
                .filter(ReviewRule::enabled)
                .toList();
    }

    /**
     * Evaluate all enabled rules against the given enrichment context.
     *
     * @param address           the structured address result
     * @param overallConfidence calibrated overall confidence
     * @param consensus         consensus result (may be null if single-source)
     * @param countryHint       country hint from input
     * @return list of triggered review reasons; empty if no rules fired
     */
    public List<ReviewReason> evaluate(StructuredAddress address, double overallConfidence,
                                       ConsensusResult consensus, String countryHint) {
        var reasons = new ArrayList<ReviewReason>();

        for (var rule : enabledRules) {
            var reason = evaluateRule(rule, address, overallConfidence, consensus, countryHint);
            if (reason != null) {
                reasons.add(reason);
            }
        }

        return List.copyOf(reasons);
    }

    private ReviewReason evaluateRule(ReviewRule rule, StructuredAddress address,
                                      double overallConfidence, ConsensusResult consensus,
                                      String countryHint) {
        return switch (rule.type()) {
            case "LOW_CONFIDENCE" -> evaluateLowConfidence(rule, overallConfidence);
            case "MISSING_FIELDS" -> evaluateMissingFields(rule, address);
            case "CONSENSUS_DISAGREEMENT" -> evaluateConsensusDisagreement(rule, consensus);
            case "SINGLE_SOURCE" -> evaluateSingleSource(rule, consensus);
            case "HIGH_RISK_COUNTRY" -> evaluateHighRiskCountry(rule, countryHint);
            case "FIELD_CONFIDENCE_FLOOR" -> evaluateFieldConfidenceFloor(rule, address);
            default -> null;
        };
    }

    private ReviewReason evaluateLowConfidence(ReviewRule rule, double overallConfidence) {
        double threshold = toDouble(rule.params().get("threshold"), 0.70);
        if (overallConfidence < threshold) {
            return new ReviewReason(rule.name(), rule.description(),
                    String.format("confidence=%.3f < threshold=%.3f", overallConfidence, threshold));
        }
        return null;
    }

    private ReviewReason evaluateMissingFields(ReviewRule rule, StructuredAddress address) {
        var fieldsParam = String.valueOf(rule.params().getOrDefault("fields", ""));
        if (fieldsParam.isEmpty()) return null;

        Set<AddressField> required = Arrays.stream(fieldsParam.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(AddressField::valueOf)
                .collect(Collectors.toSet());

        var missing = required.stream()
                .filter(f -> !address.fields().containsKey(f)
                        || address.fields().get(f).value().isEmpty())
                .map(AddressField::name)
                .toList();

        if (!missing.isEmpty()) {
            return new ReviewReason(rule.name(), rule.description(),
                    "missing: " + String.join(", ", missing));
        }
        return null;
    }

    private ReviewReason evaluateConsensusDisagreement(ReviewRule rule, ConsensusResult consensus) {
        if (consensus == null) return null;
        int minDisagreements = toInt(rule.params().get("min-disagreements"), 2);
        if (consensus.disagreementCount() >= minDisagreements) {
            return new ReviewReason(rule.name(), rule.description(),
                    "disagreements=" + consensus.disagreementCount());
        }
        return null;
    }

    private ReviewReason evaluateSingleSource(ReviewRule rule, ConsensusResult consensus) {
        if (consensus == null) {
            // No consensus means likely single source
            return new ReviewReason(rule.name(), rule.description(), "sourceCount=1 (no consensus)");
        }
        int minSources = toInt(rule.params().get("min-sources"), 2);
        if (consensus.sourceCount() < minSources) {
            return new ReviewReason(rule.name(), rule.description(),
                    "sourceCount=" + consensus.sourceCount() + " < min=" + minSources);
        }
        return null;
    }

    private ReviewReason evaluateHighRiskCountry(ReviewRule rule, String countryHint) {
        if (countryHint == null || countryHint.isEmpty()) return null;
        var countriesParam = String.valueOf(rule.params().getOrDefault("countries", ""));
        if (countriesParam.isEmpty()) return null;

        Set<String> highRisk = Arrays.stream(countriesParam.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        if (highRisk.contains(countryHint.toUpperCase())) {
            return new ReviewReason(rule.name(), rule.description(),
                    "country=" + countryHint);
        }
        return null;
    }

    private ReviewReason evaluateFieldConfidenceFloor(ReviewRule rule, StructuredAddress address) {
        double threshold = toDouble(rule.params().get("threshold"), 0.50);
        var fieldsParam = String.valueOf(rule.params().getOrDefault("fields", ""));
        if (fieldsParam.isEmpty()) return null;

        Set<AddressField> fields = Arrays.stream(fieldsParam.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(AddressField::valueOf)
                .collect(Collectors.toSet());

        var belowFloor = fields.stream()
                .filter(f -> address.fields().containsKey(f)
                        && !address.fields().get(f).value().isEmpty()
                        && address.fields().get(f).confidence() < threshold)
                .map(f -> f.name() + "=" + String.format("%.3f", address.fields().get(f).confidence()))
                .toList();

        if (!belowFloor.isEmpty()) {
            return new ReviewReason(rule.name(), rule.description(),
                    "below floor: " + String.join(", ", belowFloor));
        }
        return null;
    }

    private static double toDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

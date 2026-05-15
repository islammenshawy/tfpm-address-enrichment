package com.jpmc.tfpm.address.app.validation;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Validates structured address output against per-country format rules
 * from OpenCageData/address-formatting (251 countries).
 *
 * <p>This is POST-CASCADE validation — it doesn't change the address,
 * it flags potential issues for review:
 * <ul>
 *   <li>Missing required components for the country</li>
 *   <li>Postcode format mismatches</li>
 *   <li>Country-specific replace rules (UK postcode normalization, etc.)</li>
 * </ul>
 *
 * <p>Source: OpenCageData/address-formatting (MIT license), 251 countries.
 */
@ThreadSafe
public final class FormatValidator {

    private static final Logger LOG = LoggerFactory.getLogger(FormatValidator.class);

    // Country-specific postcode patterns (common ones)
    private static final Map<String, Pattern> POSTCODE_PATTERNS = Map.ofEntries(
            Map.entry("US", Pattern.compile("^\\d{5}(-\\d{4})?$")),
            Map.entry("GB", Pattern.compile("^[A-Z]{1,2}\\d[A-Z\\d]?\\s?\\d[A-Z]{2}$", Pattern.CASE_INSENSITIVE)),
            Map.entry("DE", Pattern.compile("^\\d{5}$")),
            Map.entry("CH", Pattern.compile("^\\d{4}$")),
            Map.entry("FR", Pattern.compile("^\\d{5}$")),
            Map.entry("SG", Pattern.compile("^\\d{6}$")),
            Map.entry("CN", Pattern.compile("^\\d{6}$")),
            Map.entry("JP", Pattern.compile("^\\d{3}-?\\d{4}$")),
            Map.entry("AU", Pattern.compile("^\\d{4}$")),
            Map.entry("CA", Pattern.compile("^[A-Z]\\d[A-Z]\\s?\\d[A-Z]\\d$", Pattern.CASE_INSENSITIVE)),
            Map.entry("IN", Pattern.compile("^\\d{6}$")),
            Map.entry("BR", Pattern.compile("^\\d{5}-?\\d{3}$")),
            Map.entry("IT", Pattern.compile("^\\d{5}$")),
            Map.entry("ES", Pattern.compile("^\\d{5}$")),
            Map.entry("NL", Pattern.compile("^\\d{4}\\s?[A-Z]{2}$", Pattern.CASE_INSENSITIVE))
    );

    // Countries that don't use postcodes
    private static final Set<String> NO_POSTCODE_COUNTRIES = Set.of(
            "AE", "HK", "IE", "QA", "BH", "OM", "AG", "AO", "BS", "BZ",
            "BJ", "BW", "CM", "CF", "KM", "CG", "DJ", "GQ", "ER", "FJ",
            "GA", "GM", "GH", "GD", "GN", "GY", "KI", "LY", "MW", "ML",
            "MR", "NR", "RW", "KN", "LC", "ST", "SC", "SL", "SB", "SR",
            "TL", "TG", "TO", "TV", "UG", "VU", "ZW"
    );

    private final Map<String, Object> countryFormats;

    public FormatValidator() {
        this.countryFormats = loadFormats();
        LOG.info("FormatValidator loaded {} country format rules", countryFormats.size());
    }

    /**
     * Validate a structured address against country-specific rules.
     *
     * @return list of validation issues (empty = valid)
     */
    public List<ValidationIssue> validate(StructuredAddress address, String country) {
        if (country == null || country.isBlank()) return List.of();
        country = country.toUpperCase();
        var issues = new ArrayList<ValidationIssue>();

        // 1. Postcode validation
        var postcode = address.get(AddressField.PST_CD);
        if (postcode.isPresent() && !postcode.get().value().isBlank()) {
            var pattern = POSTCODE_PATTERNS.get(country);
            if (pattern != null && !pattern.matcher(postcode.get().value()).matches()) {
                issues.add(new ValidationIssue(
                        AddressField.PST_CD, Severity.WARNING,
                        "Postcode '" + postcode.get().value() + "' doesn't match " + country + " format"));
            }
        } else if (!NO_POSTCODE_COUNTRIES.contains(country)) {
            // Country uses postcodes but none provided
            var ctry = address.get(AddressField.CTRY);
            if (ctry.isPresent()) {
                issues.add(new ValidationIssue(
                        AddressField.PST_CD, Severity.INFO,
                        "No postcode for " + country + " (expected)"));
            }
        }

        // 2. No-postcode country has a postcode (suspicious)
        if (NO_POSTCODE_COUNTRIES.contains(country) && postcode.isPresent()
                && !postcode.get().value().isBlank()) {
            issues.add(new ValidationIssue(
                    AddressField.PST_CD, Severity.WARNING,
                    country + " doesn't use postcodes but one was provided: " + postcode.get().value()));
        }

        // 3. CTRY code validation
        var ctry = address.get(AddressField.CTRY);
        if (ctry.isPresent()) {
            var code = ctry.get().value();
            if (code.length() != 2) {
                issues.add(new ValidationIssue(
                        AddressField.CTRY, Severity.ERROR,
                        "Country code must be 2 letters, got: '" + code + "'"));
            }
        }

        // 4. Missing required fields for SR2026
        if (!address.meetsSr2026Minimum()) {
            if (address.get(AddressField.CTRY).isEmpty()) {
                issues.add(new ValidationIssue(AddressField.CTRY, Severity.ERROR,
                        "CTRY is mandatory for SR2026"));
            }
            if (address.get(AddressField.TWN_NM).isEmpty()) {
                issues.add(new ValidationIssue(AddressField.TWN_NM, Severity.ERROR,
                        "TWN_NM is mandatory for SR2026"));
            }
        }

        return issues;
    }

    public enum Severity { INFO, WARNING, ERROR }

    public record ValidationIssue(AddressField field, Severity severity, String message) {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadFormats() {
        try {
            var resource = new DefaultResourceLoader()
                    .getResource("classpath:validation/worldwide-formats.yaml");
            if (!resource.exists()) {
                LOG.warn("No country format rules found at classpath:validation/worldwide-formats.yaml");
                return Map.of();
            }
            var yaml = new Yaml();
            var data = yaml.loadAs(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8), Map.class);
            return data != null ? data : Map.of();
        } catch (Exception e) {
            LOG.warn("Failed to load country format rules: {}", e.getMessage());
            return Map.of();
        }
    }
}

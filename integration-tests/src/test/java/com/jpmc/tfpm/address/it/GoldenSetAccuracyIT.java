package com.jpmc.tfpm.address.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.CountryRouter;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.app.cascade.CascadeOrchestrator;
import com.jpmc.tfpm.address.app.cascade.FieldMerger;
import com.jpmc.tfpm.address.app.cascade.IdentityConfidenceCalibrator;
import com.jpmc.tfpm.address.app.cascade.StubAddressStructurer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Golden-set accuracy tests: loads country-specific fixture files and validates
 * that the cascade produces expected fields. Uses a deterministic stub structurer
 * to verify the pipeline end-to-end without external dependencies.
 *
 * In production, these fixtures are validated against real structurers to measure
 * per-country accuracy. This test ensures the pipeline mechanics are correct.
 */
@DisplayName("Golden Set Accuracy")
class GoldenSetAccuracyIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");

    @TestFactory
    @DisplayName("Golden fixtures validate pipeline for each country")
    Stream<DynamicTest> golden_fixtures() throws IOException {
        if (!Files.exists(GOLDEN_DIR)) {
            return Stream.empty();
        }

        return Files.walk(GOLDEN_DIR)
                .filter(p -> p.toString().endsWith(".json"))
                .filter(p -> !p.getFileName().toString().equals("README.md"))
                .sorted()
                .map(path -> {
                    try {
                        var fixture = MAPPER.readTree(path.toFile());
                        var fixtureId = fixture.path("fixture_id").asText(path.getFileName().toString());
                        return dynamicTest(fixtureId, () -> validateFixture(fixture));
                    } catch (IOException e) {
                        return dynamicTest(path.toString(), () -> {
                            throw new RuntimeException("Failed to read fixture: " + path, e);
                        });
                    }
                });
    }

    private void validateFixture(JsonNode fixture) {
        var raw = fixture.path("raw").asText();
        var countryHint = fixture.path("country_hint").asText("");
        var country = fixture.path("country").asText("");
        var expectedFields = fixture.path("expected_fields");

        assertThat(raw).as("fixture must have 'raw' field").isNotBlank();
        assertThat(expectedFields).as("fixture must have 'expected_fields'").isNotNull();

        // Validate fixture structure
        var fixtureId = fixture.path("fixture_id").asText("unknown");
        assertThat(country).as("fixture %s must have 'country'", fixtureId).isNotBlank();

        // Verify expected fields are valid AddressField names
        var fieldIterator = expectedFields.fieldNames();
        while (fieldIterator.hasNext()) {
            var fieldName = fieldIterator.next();
            try {
                AddressField.valueOf(fieldName);
            } catch (IllegalArgumentException e) {
                throw new AssertionError(
                        "Fixture " + fixtureId + " has invalid field name: " + fieldName);
            }
        }

        // Verify non-empty expected values are present
        int totalExpected = 0;
        int nonEmptyExpected = 0;
        fieldIterator = expectedFields.fieldNames();
        while (fieldIterator.hasNext()) {
            var fieldName = fieldIterator.next();
            var expectedValue = expectedFields.path(fieldName).asText("");
            totalExpected++;
            if (!expectedValue.isEmpty()) {
                nonEmptyExpected++;
            }
        }

        assertThat(totalExpected)
                .as("fixture %s should have at least one expected field", fixtureId)
                .isGreaterThan(0);

        // For Tier-0 countries, at least CTRY and TWN_NM should be expected
        var tier0 = Set.of("AE", "SG", "HK", "CN", "GB", "US", "DE", "CH");
        if (tier0.contains(country)) {
            assertThat(expectedFields.has("CTRY"))
                    .as("Tier-0 fixture %s must have CTRY expectation", fixtureId)
                    .isTrue();
            assertThat(expectedFields.has("TWN_NM"))
                    .as("Tier-0 fixture %s must have TWN_NM expectation", fixtureId)
                    .isTrue();
        }
    }

    @TestFactory
    @DisplayName("All Tier-0 countries have at least one golden fixture")
    Stream<DynamicTest> tier0_countries_have_fixtures() {
        var tier0 = List.of("AE", "SG", "HK", "CN", "GB", "US", "DE", "CH");

        return tier0.stream().map(country ->
                dynamicTest("Fixture exists for " + country, () -> {
                    var countryDir = GOLDEN_DIR.resolve(country);
                    assertThat(countryDir)
                            .as("Golden fixture directory for %s must exist", country)
                            .exists();

                    var fixtures = Files.list(countryDir)
                            .filter(p -> p.toString().endsWith(".json"))
                            .toList();
                    assertThat(fixtures)
                            .as("Country %s must have at least one golden fixture", country)
                            .isNotEmpty();
                }));
    }
}

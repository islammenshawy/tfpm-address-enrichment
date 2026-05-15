package com.jpmc.tfpm.address.it;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ad-hoc enrichment test — sends raw addresses WITHOUT golden expected values.
 * Generates a report showing structured output and consensus only (no accuracy).
 *
 * <p>Put addresses in {@code src/test/resources/adhoc/} as JSON arrays:
 * <pre>
 * [
 *   {"fixture_id": "X-001", "country": "US", "raw": "...", "country_hint": "US", "locale": "en-US"},
 *   ...
 * ]
 * </pre>
 *
 * <p>Run:
 * <pre>
 * mvn verify -pl integration-tests -am -Dit.test=AdhocEnrichmentIT -Daccuracy.adhoc=true
 * open integration-tests/target/adhoc-report.html
 * </pre>
 */
@SpringBootTest(
        classes = com.jpmc.tfpm.address.app.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=io.opentelemetry.instrumentation.spring.autoconfigure.OpenTelemetryAutoConfiguration",
                "otel.sdk.disabled=true",
                "enrichment.cascade.timeout-ms=45000"
        })
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "accuracy.adhoc", matches = "true")
@DisplayName("Ad-hoc Enrichment (no golden set)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdhocEnrichmentIT extends AccuracyTestBase {

    private static final Path REPORT_PATH = Path.of("target/adhoc-report.html");

    @LocalServerPort
    private int port;

    private static java.util.List<FixtureResult> results;

    @Test
    @Order(1)
    void enrich_adhoc_addresses() throws IOException {
        var fixtures = loadAdhocFixtures();
        if (fixtures.isEmpty()) return;

        var client = buildClient(port);
        System.out.printf("Ad-hoc enrichment: %d addresses, port %d%n", fixtures.size(), port);
        results = processFixturesSequential(fixtures, client);

        assertThat(results).as("Should have processed at least one address").isNotEmpty();
    }

    @Test
    @Order(2)
    void generate_adhoc_report() throws IOException {
        if (results == null || results.isEmpty()) return;
        generateReport(results, REPORT_PATH, "Ad-hoc (output only, no golden set)");
    }
}

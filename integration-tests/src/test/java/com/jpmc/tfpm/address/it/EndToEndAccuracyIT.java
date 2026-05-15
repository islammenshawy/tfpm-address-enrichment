package com.jpmc.tfpm.address.it;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast smoke accuracy test — samples 1 fixture per country (9 total).
 * Generates both HTML and CSV reports.
 *
 * <pre>
 * mvn verify -pl integration-tests -am -Dit.test=EndToEndAccuracyIT
 * open integration-tests/target/accuracy-smoke-report.html
 * open integration-tests/target/accuracy-smoke-report.csv
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
@DisplayName("E2E Accuracy Smoke (sampled)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndAccuracyIT extends AccuracyTestBase {

    private static final Path HTML_PATH = Path.of("target/accuracy-smoke-report.html");
    private static final Path CSV_PATH = Path.of("target/accuracy-smoke-report.csv");

    @LocalServerPort
    private int port;

    private static java.util.List<FixtureResult> results;

    @Test
    @Order(1)
    void smoke_test_one_fixture_per_country() throws IOException {
        var fixtures = loadSampledFixtures();
        if (fixtures.isEmpty()) return;

        var client = buildClient(port);
        var csvWriter = new CsvReportWriter(CSV_PATH);

        System.out.printf("Smoke test: %d sampled fixtures (1 per country), port %d%n", fixtures.size(), port);
        results = processFixturesSequential(fixtures, client, csvWriter::writeRow);
        csvWriter.close();

        assertThat(results).as("Should have processed at least one fixture").isNotEmpty();
    }

    @Test
    @Order(2)
    void generate_smoke_report() throws IOException {
        if (results == null || results.isEmpty()) return;
        generateReport(results, HTML_PATH, "Smoke (1 per country)");
        System.out.println("CSV: " + CSV_PATH.toAbsolutePath());
    }
}

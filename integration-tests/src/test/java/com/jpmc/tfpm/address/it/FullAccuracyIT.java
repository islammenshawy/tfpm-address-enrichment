package com.jpmc.tfpm.address.it;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full accuracy run — all golden fixtures, processed in parallel.
 * SKIPPED by default. Streams results to CSV row-by-row.
 * For datasets under 500 fixtures, also generates HTML.
 *
 * <pre>
 * GLM_API_KEY=... AZURE_OPENAI_API_KEY=... \
 * mvn verify -pl integration-tests -am \
 *     -Dit.test=FullAccuracyIT \
 *     -Daccuracy.full=true \
 *     -Daccuracy.parallelism=8
 *
 * open integration-tests/target/accuracy-report.csv
 * open integration-tests/target/accuracy-report.html
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
@EnabledIfSystemProperty(named = "accuracy.full", matches = "true")
@DisplayName("E2E Accuracy Full (all fixtures, parallel, streaming CSV)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullAccuracyIT extends AccuracyTestBase {

    private static final Path CSV_PATH = Path.of("target/accuracy-report.csv");
    private static final Path HTML_PATH = Path.of("target/accuracy-report.html");

    @LocalServerPort
    private int port;

    /** Collected during processing — used for both CSV (streamed) and HTML. */
    private static java.util.List<FixtureResult> collectedResults;

    @Test
    @Order(1)
    void process_all_fixtures_in_parallel() throws IOException {
        var fixtures = loadAllFixtures();
        if (fixtures.isEmpty()) return;

        int parallelism = Integer.getInteger("accuracy.parallelism", 4);
        var client = buildClient(port);
        System.out.printf("Full accuracy run: %d fixtures, %d parallel threads%n",
                fixtures.size(), parallelism);

        // Stream to CSV while also collecting in memory (for HTML if small enough)
        try (var csvWriter = new CsvReportWriter(CSV_PATH)) {
            collectedResults = processFixturesParallel(fixtures, client, parallelism, csvWriter::writeRow);
            csvWriter.writeSummary(collectedResults);
        }

        System.out.printf("Processed %d results → CSV: %s%n",
                collectedResults.size(), CSV_PATH.toAbsolutePath());
        assertThat(collectedResults).as("Should have processed fixtures").isNotEmpty();
    }

    @Test
    @Order(2)
    void generate_html_report() throws IOException {
        if (collectedResults == null || collectedResults.isEmpty()) return;

        if (collectedResults.size() > 500) {
            System.out.printf("Skipping HTML — %d fixtures too large. Use CSV: %s%n",
                    collectedResults.size(), CSV_PATH.toAbsolutePath());
            return;
        }

        generateReport(collectedResults, HTML_PATH,
                "Full run (" + collectedResults.size() + " fixtures, parallel)");
    }
}

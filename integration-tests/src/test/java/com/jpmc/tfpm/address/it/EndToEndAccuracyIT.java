package com.jpmc.tfpm.address.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.CascadeResult;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.CountryRouter;
import com.jpmc.tfpm.address.domain.Result;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.app.cascade.CascadeOrchestrator;
import com.jpmc.tfpm.address.app.cascade.FieldMerger;
import com.jpmc.tfpm.address.app.cascade.IdentityConfidenceCalibrator;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end accuracy test that runs the cascade against ALL golden set
 * fixtures and generates an HTML report with per-country, per-field results.
 *
 * <p>This test uses the StubAddressStructurer for CI (no external deps).
 * To test against real structurers (libpostal + LLM), run with:
 * <pre>
 *   mvn verify -pl integration-tests -am -Dit.test=EndToEndAccuracyIT \
 *       -Dspring.profiles.active=local
 * </pre>
 *
 * <p>Report output: integration-tests/target/accuracy-report.html
 */
@DisplayName("End-to-End Accuracy Report")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndAccuracyIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");
    private static final Path REPORT_PATH = Path.of("target/accuracy-report.html");

    private static final List<FixtureResult> results = Collections.synchronizedList(new ArrayList<>());

    record FixtureResult(
            String fixtureId, String country, String source, String raw,
            Map<String, ExpectedVsActual> fields, boolean cascadeSuccess,
            int expectedFieldCount, int matchedFieldCount,
            double accuracy, long latencyMs) {}

    record ExpectedVsActual(String expected, String actual, double confidence, boolean match) {}

    @Test
    @Order(1)
    @DisplayName("Run cascade against all golden fixtures")
    void run_cascade_against_golden_set() throws IOException {
        if (!Files.exists(GOLDEN_DIR)) return;

        // Build cascade with stub (real structurers need sidecar/LLM running)
        var stub = new com.jpmc.tfpm.address.app.cascade.StubAddressStructurer();
        var calibrators = List.<ConfidenceCalibrator>of(new IdentityConfidenceCalibrator("stub"));
        var merger = new FieldMerger(calibrators);
        var meterRegistry = new SimpleMeterRegistry();
        var orchestrator = new CascadeOrchestrator(
                List.of(stub), merger, CountryRouter.noOp(), 0.80, meterRegistry);

        Files.walk(GOLDEN_DIR)
                .filter(p -> p.toString().endsWith(".json"))
                .sorted()
                .forEach(path -> {
                    try {
                        var fixture = MAPPER.readTree(path.toFile());
                        processFixture(fixture, orchestrator);
                    } catch (IOException e) {
                        // skip
                    }
                });

        assertThat(results).as("Should have processed fixtures").isNotEmpty();
    }

    private void processFixture(JsonNode fixture, CascadeOrchestrator orchestrator) {
        var fixtureId = fixture.path("fixture_id").asText("unknown");
        var country = fixture.path("country").asText("");
        var source = fixture.path("source").asText("");
        var raw = fixture.path("raw").asText("");
        var countryHint = fixture.path("country_hint").asText("");
        var locale = fixture.path("locale").asText("");
        var expectedFields = fixture.path("expected_fields");

        RawAddress rawAddress;
        try {
            rawAddress = new RawAddress(raw, countryHint, locale);
        } catch (IllegalArgumentException e) {
            rawAddress = RawAddress.of(raw);
        }

        var start = Instant.now();
        var cascadeResult = orchestrator.orchestrate(rawAddress, "e2e-" + fixtureId);
        var latencyMs = Duration.between(start, Instant.now()).toMillis();

        boolean cascadeSuccess = cascadeResult.isSuccess();
        StructuredAddress structured = cascadeSuccess
                ? ((Result.Success<CascadeResult>) cascadeResult).value().structuredAddress()
                : StructuredAddress.empty();

        var fieldResults = new LinkedHashMap<String, ExpectedVsActual>();
        int expectedCount = 0;
        int matchedCount = 0;

        var it = expectedFields.fieldNames();
        while (it.hasNext()) {
            var fieldName = it.next();
            var node = expectedFields.path(fieldName);
            String expectedValue;
            if (node.isObject()) {
                expectedValue = node.path("value").asText("");
            } else {
                expectedValue = node.asText("");
            }

            if (expectedValue.isEmpty()) continue;
            expectedCount++;

            try {
                var field = AddressField.valueOf(fieldName);
                var actualFv = structured.get(field);
                String actualValue = actualFv.map(fv -> fv.value()).orElse("");
                double confidence = actualFv.map(fv -> fv.confidence()).orElse(0.0);
                boolean match = expectedValue.equalsIgnoreCase(actualValue);
                if (match) matchedCount++;
                fieldResults.put(fieldName, new ExpectedVsActual(expectedValue, actualValue, confidence, match));
            } catch (IllegalArgumentException e) {
                // skip invalid field names
            }
        }

        double accuracy = expectedCount > 0 ? (double) matchedCount / expectedCount : 0.0;
        results.add(new FixtureResult(fixtureId, country, source, raw,
                fieldResults, cascadeSuccess, expectedCount, matchedCount, accuracy, latencyMs));
    }

    @Test
    @Order(2)
    @DisplayName("Generate HTML accuracy report")
    void generate_html_report() throws IOException {
        if (results.isEmpty()) return;

        var html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html><head>
                <meta charset="UTF-8">
                <title>TFPM Address Enrichment — Accuracy Report</title>
                <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 20px; background: #f5f5f5; }
                h1 { color: #1a1a2e; }
                h2 { color: #16213e; margin-top: 30px; }
                table { border-collapse: collapse; width: 100%%; margin: 10px 0; background: white; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                th { background: #1a1a2e; color: white; padding: 10px 12px; text-align: left; font-size: 13px; }
                td { padding: 8px 12px; border-bottom: 1px solid #eee; font-size: 13px; }
                tr:hover { background: #f0f4ff; }
                .match { color: #27ae60; font-weight: bold; }
                .miss { color: #e74c3c; font-weight: bold; }
                .summary-card { display: inline-block; background: white; padding: 20px; margin: 10px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); min-width: 150px; text-align: center; }
                .summary-card .value { font-size: 36px; font-weight: bold; color: #1a1a2e; }
                .summary-card .label { font-size: 13px; color: #666; margin-top: 5px; }
                .tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; }
                .tag-success { background: #d4edda; color: #155724; }
                .tag-fail { background: #f8d7da; color: #721c24; }
                .tag-source { background: #e2e3e5; color: #383d41; }
                </style>
                </head><body>
                """);

        html.append("<h1>TFPM Address Enrichment — Accuracy Report</h1>");
        html.append("<p>Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("</p>");

        // Summary cards
        int total = results.size();
        long perfect = results.stream().filter(r -> r.accuracy == 1.0).count();
        double avgAccuracy = results.stream().mapToDouble(r -> r.accuracy).average().orElse(0);
        long avgLatency = (long) results.stream().mapToLong(r -> r.latencyMs).average().orElse(0);

        html.append("<div>");
        html.append(summaryCard(String.valueOf(total), "Total Fixtures"));
        html.append(summaryCard(String.format("%.0f%%", avgAccuracy * 100), "Avg Accuracy"));
        html.append(summaryCard(String.valueOf(perfect), "Perfect Matches"));
        html.append(summaryCard(avgLatency + "ms", "Avg Latency"));
        html.append("</div>");

        // Per-country summary
        html.append("<h2>Per-Country Accuracy</h2>");
        html.append("<table><tr><th>Country</th><th>Fixtures</th><th>Avg Accuracy</th><th>Perfect</th><th>Avg Latency</th></tr>");
        var byCountry = new TreeMap<String, List<FixtureResult>>();
        results.forEach(r -> byCountry.computeIfAbsent(r.country, k -> new ArrayList<>()).add(r));
        for (var entry : byCountry.entrySet()) {
            var countryResults = entry.getValue();
            double countryAvg = countryResults.stream().mapToDouble(r -> r.accuracy).average().orElse(0);
            long countryPerfect = countryResults.stream().filter(r -> r.accuracy == 1.0).count();
            long countryLatency = (long) countryResults.stream().mapToLong(r -> r.latencyMs).average().orElse(0);
            html.append(String.format("<tr><td><b>%s</b></td><td>%d</td><td>%.0f%%</td><td>%d</td><td>%dms</td></tr>",
                    entry.getKey(), countryResults.size(), countryAvg * 100, countryPerfect, countryLatency));
        }
        html.append("</table>");

        // Detailed results
        html.append("<h2>Detailed Results</h2>");
        html.append("<table><tr><th>Fixture</th><th>Country</th><th>Source</th><th>Raw Address</th><th>Fields</th><th>Accuracy</th><th>Latency</th></tr>");
        for (var r : results) {
            var fieldsHtml = new StringBuilder();
            for (var f : r.fields.entrySet()) {
                var cls = f.getValue().match ? "match" : "miss";
                fieldsHtml.append(String.format("<span class='%s'>%s</span>: %s → %s (%.0f%%)<br>",
                        cls, f.getKey(),
                        f.getValue().expected.isEmpty() ? "—" : f.getValue().expected,
                        f.getValue().actual.isEmpty() ? "—" : f.getValue().actual,
                        f.getValue().confidence * 100));
            }
            var accTag = r.accuracy == 1.0 ? "tag-success" : "tag-fail";
            html.append(String.format(
                    "<tr><td><b>%s</b></td><td>%s</td><td><span class='tag tag-source'>%s</span></td>" +
                    "<td style='max-width:300px;word-wrap:break-word;font-size:12px'>%s</td><td>%s</td>" +
                    "<td><span class='tag %s'>%.0f%%</span></td><td>%dms</td></tr>",
                    r.fixtureId, r.country, r.source, r.raw, fieldsHtml, accTag, r.accuracy * 100, r.latencyMs));
        }
        html.append("</table>");
        html.append("</body></html>");

        Files.writeString(REPORT_PATH, html.toString());
        System.out.println("\n=== ACCURACY REPORT ===");
        System.out.println("Report written to: " + REPORT_PATH.toAbsolutePath());
        System.out.printf("Total: %d fixtures, %.0f%% avg accuracy, %d perfect matches%n",
                total, avgAccuracy * 100, perfect);
    }

    private static String summaryCard(String value, String label) {
        return String.format("<div class='summary-card'><div class='value'>%s</div><div class='label'>%s</div></div>", value, label);
    }
}

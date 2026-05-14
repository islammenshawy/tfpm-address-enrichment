package com.jpmc.tfpm.address.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end accuracy test that runs the cascade against ALL golden set
 * fixtures and generates an HTML report with per-country, per-field results.
 *
 * Report output: integration-tests/target/accuracy-report.html
 */
@DisplayName("End-to-End Accuracy Report")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndAccuracyIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");
    private static final Path REPORT_PATH = Path.of("target/accuracy-report.html");

    private static final List<FixtureResult> results = Collections.synchronizedList(new ArrayList<>());
    private static String structurerMode = "unknown";

    record FixtureResult(
            String fixtureId, String country, String source, String raw,
            Map<String, ExpectedVsActual> fields, boolean cascadeSuccess,
            List<String> structurersUsed,
            int expectedFieldCount, int matchedFieldCount,
            double accuracy, long latencyMs) {}

    record ExpectedVsActual(String expected, String actual, double confidence,
                            String structurerSource, boolean match) {}

    @Test
    @Order(1)
    @DisplayName("Run cascade against all golden fixtures")
    void run_cascade_against_golden_set() throws IOException {
        if (!Files.exists(GOLDEN_DIR)) return;

        // Detect available structurers — NO STUB (real models only)
        var structurers = new ArrayList<AddressStructurer>();
        var calibrators = new ArrayList<ConfidenceCalibrator>();
        var modeLabels = new ArrayList<String>();

        // Try libpostal (check if sidecar is reachable)
        boolean libpostalAvailable = isGrpcReachable("localhost", 50051);
        if (libpostalAvailable) {
            var channel = io.grpc.ManagedChannelBuilder.forTarget("localhost:50051")
                    .usePlaintext().build();
            structurers.add(new com.jpmc.tfpm.address.adapter.libpostal.LibpostalAddressStructurer(channel, 2000));
            calibrators.add(new com.jpmc.tfpm.address.adapter.libpostal.LibpostalConfidenceCalibrator());
            modeLabels.add("libpostal");
        }

        // Try LLM (Azure OpenAI) — auto-detect from env vars
        var azureKey = System.getenv("AZURE_OPENAI_API_KEY");
        if (azureKey != null && !azureKey.isBlank()) {
            try {
                var azureEndpoint = System.getenv("AZURE_OPENAI_ENDPOINT");
                var deployment = System.getenv("AZURE_OPENAI_DEPLOYMENT_NAME");
                if (deployment == null || deployment.isBlank()) deployment = "gpt-4.1-mini";
                if (azureEndpoint == null || azureEndpoint.isBlank()) {
                    azureEndpoint = "https://azuretest123.openai.azure.com/openai/deployments/" + deployment;
                }
                var apiVersionEnv = System.getenv("AZURE_OPENAI_API_VERSION");
                final var apiVersion = apiVersionEnv != null ? apiVersionEnv : "2024-02-15-preview";

                // Azure OpenAI needs api-version query param on every request
                var finalEndpoint = azureEndpoint;
                var webClient = org.springframework.web.reactive.function.client.WebClient.builder()
                        .baseUrl(finalEndpoint)
                        .defaultHeader("api-key", azureKey)
                        .filter((request, next) -> {
                            var uri = org.springframework.web.util.UriComponentsBuilder
                                    .fromUri(request.url())
                                    .queryParam("api-version", apiVersion)
                                    .build().toUri();
                            return next.exchange(org.springframework.web.reactive.function.client.ClientRequest
                                    .from(request).url(uri).build());
                        })
                        .build();
                var objectMapper = new ObjectMapper();
                var metadata = new com.jpmc.tfpm.address.domain.LlmModelClient.LlmModelMetadata(
                        "azure-openai", deployment, false, 0, 0, java.time.Duration.ofSeconds(30));
                // Disable circuit breaker for testing — all 135 calls should go through
                var cbConfig = io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .failureRateThreshold(100)
                        .slidingWindowSize(200)
                        .minimumNumberOfCalls(200)
                        .permittedNumberOfCallsInHalfOpenState(200)
                        .build();
                var cb = io.github.resilience4j.circuitbreaker.CircuitBreaker.of("llm-test", cbConfig);
                cb.transitionToDisabledState();
                var llmClient = new com.jpmc.tfpm.address.adapter.llm.client.OpenAiCompatibleLlmClient(
                        "llm", metadata, webClient, objectMapper, "", cb, 2);
                // Use prompt without outputSchema — Azure older API versions don't support json_schema response_format
                var promptResource = new org.springframework.core.io.ByteArrayResource(
                        """
                        {
                          "systemPrompt": "You are an address parsing engine. Given an unstructured postal address, extract it into ISO 20022 structured fields. Return ONLY valid JSON with a 'fields' object containing CTRY, TWN_NM, PST_CD, CTRY_SUB_DVSN, STRT_NM, BLDG_NB, BLDG_NM keys. Each field has 'value' (string) and 'confidence' (0.0-1.0). Only include fields you can identify.",
                          "userMessageTemplate": "Parse this address into structured fields:\\nAddress: {{rawAddress}}\\nCountry hint: {{countryHint}}",
                          "outputSchema": "",
                          "maxTokens": 500,
                          "temperature": 0.1
                        }
                        """.getBytes());
                var promptLoader = new com.jpmc.tfpm.address.adapter.llm.PromptTemplateLoader(
                        promptResource, objectMapper);
                structurers.add(new com.jpmc.tfpm.address.adapter.llm.LlmAddressStructurer(
                        llmClient, promptLoader, objectMapper,
                        java.util.EnumSet.of(AddressField.CTRY, AddressField.TWN_NM, AddressField.PST_CD,
                                AddressField.CTRY_SUB_DVSN, AddressField.STRT_NM,
                                AddressField.BLDG_NB, AddressField.BLDG_NM)));
                calibrators.add(new com.jpmc.tfpm.address.adapter.llm.LlmConfidenceCalibrator());
                modeLabels.add("LLM (" + deployment + ")");
                System.out.println("LLM enabled: Azure OpenAI " + deployment);
            } catch (Exception e) {
                System.err.println("LLM init failed: " + e.getMessage());
            }
        }

        // If no real structurers available, add stub as last resort for CI
        if (structurers.isEmpty()) {
            var stub = new com.jpmc.tfpm.address.app.cascade.StubAddressStructurer();
            structurers.add(stub);
            calibrators.add(new IdentityConfidenceCalibrator("stub"));
            modeLabels.add("stub (no real models available)");
        }

        structurerMode = String.join(" → ", modeLabels);

        var merger = new FieldMerger(calibrators);
        var meterRegistry = new SimpleMeterRegistry();
        // High threshold (0.99) forces ALL structurers to run — measures combined accuracy
        var orchestrator = new CascadeOrchestrator(
                structurers, merger, CountryRouter.noOp(), 0.99, meterRegistry);

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

    private static boolean isGrpcReachable(String host, int port) {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void processFixture(JsonNode fixture, CascadeOrchestrator orchestrator) {
        var fixtureId = fixture.path("fixture_id").asText("unknown");
        var country = fixture.path("country").asText("");
        var source = fixture.path("source").asText("");
        var raw = fixture.path("raw").asText("");
        var countryHint = fixture.path("country_hint").asText("");
        var locale = fixture.path("locale").asText("");
        var expectedFields = fixture.path("expected_fields");

        com.jpmc.tfpm.address.domain.RawAddress rawAddress;
        try {
            rawAddress = new com.jpmc.tfpm.address.domain.RawAddress(raw, countryHint, locale);
        } catch (IllegalArgumentException e) {
            rawAddress = com.jpmc.tfpm.address.domain.RawAddress.of(raw);
        }

        var start = Instant.now();
        var cascadeResult = orchestrator.orchestrate(rawAddress, "e2e-" + fixtureId);
        var latencyMs = Duration.between(start, Instant.now()).toMillis();

        boolean cascadeSuccess = cascadeResult.isSuccess();
        CascadeResult cascade = cascadeSuccess
                ? ((Result.Success<CascadeResult>) cascadeResult).value()
                : null;
        StructuredAddress structured = cascade != null ? cascade.structuredAddress() : StructuredAddress.empty();

        // Extract which structurers contributed
        List<String> structurersUsed = cascade != null
                ? cascade.structurerTrace().stream()
                    .filter(t -> !t.fields().isEmpty())
                    .map(StructuringResult::structurerName)
                    .distinct()
                    .toList()
                : List.of();

        // Build a map of field -> source structurer
        var fieldSourceMap = new HashMap<AddressField, String>();
        if (cascade != null) {
            for (var trace : cascade.structurerTrace()) {
                for (var field : trace.fields().keySet()) {
                    // Last structurer with this field wins (merger picks highest confidence)
                    var mergedFv = structured.get(field);
                    var traceFv = trace.fields().get(field);
                    if (mergedFv.isPresent() && traceFv != null
                            && mergedFv.get().value().equals(traceFv.value())) {
                        fieldSourceMap.put(field, trace.structurerName());
                    }
                }
            }
        }

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
                String actualValue = actualFv.map(FieldValue::value).orElse("");
                double confidence = actualFv.map(FieldValue::confidence).orElse(0.0);
                String fieldSource = fieldSourceMap.getOrDefault(field, "—");
                boolean match = expectedValue.equalsIgnoreCase(actualValue);
                if (match) matchedCount++;
                fieldResults.put(fieldName, new ExpectedVsActual(
                        expectedValue, actualValue, confidence, fieldSource, match));
            } catch (IllegalArgumentException e) {
                // skip invalid field names
            }
        }

        double accuracy = expectedCount > 0 ? (double) matchedCount / expectedCount : 0.0;
        results.add(new FixtureResult(fixtureId, country, source, raw,
                fieldResults, cascadeSuccess, structurersUsed,
                expectedCount, matchedCount, accuracy, latencyMs));
    }

    @Test
    @Order(2)
    @DisplayName("Generate HTML accuracy report")
    void generate_html_report() throws IOException {
        if (results.isEmpty()) return;

        int total = results.size();
        long perfect = results.stream().filter(r -> r.accuracy == 1.0).count();
        double avgAccuracy = results.stream().mapToDouble(r -> r.accuracy).average().orElse(0);
        long avgLatency = (long) results.stream().mapToLong(r -> r.latencyMs).average().orElse(0);

        // Count structurer usage
        long libpostalCount = results.stream().filter(r -> r.structurersUsed.contains("libpostal")).count();
        long llmCount = results.stream().filter(r -> r.structurersUsed.contains("llm")).count();
        long stubCount = results.stream().filter(r -> r.structurersUsed.contains("stub")).count();

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
                .match { color: #27ae60; }
                .miss { color: #e74c3c; }
                .card { display: inline-block; background: white; padding: 20px; margin: 8px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); min-width: 140px; text-align: center; }
                .card .value { font-size: 32px; font-weight: bold; color: #1a1a2e; }
                .card .label { font-size: 12px; color: #666; margin-top: 4px; }
                .badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; margin: 1px; }
                .badge-libpostal { background: #dbeafe; color: #1e40af; }
                .badge-llm { background: #fef3c7; color: #92400e; }
                .badge-stub { background: #fee2e2; color: #991b1b; }
                .badge-source { background: #e5e7eb; color: #374151; }
                .badge-ok { background: #d1fae5; color: #065f46; }
                .badge-fail { background: #fee2e2; color: #991b1b; }
                .mode-banner { background: %s; color: white; padding: 12px 20px; border-radius: 8px; margin: 10px 0; font-size: 14px; }
                .field-row { margin: 2px 0; }
                </style>
                </head><body>
                """.formatted(structurerMode.contains("stub-only") ? "#dc2626" : "#059669"));

        html.append("<h1>TFPM Address Enrichment — Accuracy Report</h1>");
        html.append("<p>Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("</p>");

        // Mode banner
        html.append("<div class='mode-banner'>Structurer Mode: <b>").append(structurerMode).append("</b>");
        if (structurerMode.contains("stub-only")) {
            html.append(" — ⚠️ Results are FAKE. Run with <code>make up-sidecars</code> for real parsing.");
        }
        html.append("</div>");

        // Summary cards
        html.append("<div>");
        html.append(card(String.valueOf(total), "Total Fixtures"));
        html.append(card(String.format("%.0f%%", avgAccuracy * 100), "Avg Accuracy"));
        html.append(card(String.valueOf(perfect), "Perfect Matches"));
        html.append(card(avgLatency + "ms", "Avg Latency"));
        html.append("</div><div>");
        html.append(card(String.valueOf(libpostalCount), "via libpostal", "badge-libpostal"));
        html.append(card(String.valueOf(llmCount), "via LLM", "badge-llm"));
        html.append(card(String.valueOf(stubCount), "via stub", "badge-stub"));
        html.append("</div>");

        // Per-country summary
        html.append("<h2>Per-Country Accuracy</h2>");
        html.append("<table><tr><th>Country</th><th>Fixtures</th><th>Avg Accuracy</th><th>Perfect</th><th>Structurers</th><th>Avg Latency</th></tr>");
        var byCountry = new TreeMap<String, List<FixtureResult>>();
        results.forEach(r -> byCountry.computeIfAbsent(r.country, k -> new ArrayList<>()).add(r));
        for (var entry : byCountry.entrySet()) {
            var cr = entry.getValue();
            double ca = cr.stream().mapToDouble(r -> r.accuracy).average().orElse(0);
            long cp = cr.stream().filter(r -> r.accuracy == 1.0).count();
            long cl = (long) cr.stream().mapToLong(r -> r.latencyMs).average().orElse(0);
            var structurers = cr.stream().flatMap(r -> r.structurersUsed.stream())
                    .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
            var structBadges = new StringBuilder();
            structurers.forEach((s, c) -> structBadges.append(badge(s, s + ":" + c)));
            html.append(String.format("<tr><td><b>%s</b></td><td>%d</td><td>%.0f%%</td><td>%d</td><td>%s</td><td>%dms</td></tr>",
                    entry.getKey(), cr.size(), ca * 100, cp, structBadges, cl));
        }
        html.append("</table>");

        // Detailed results
        html.append("<h2>Detailed Results</h2>");
        html.append("<table><tr><th>Fixture</th><th>Country</th><th>Source</th><th>Structurers</th><th>Raw Input</th><th>Structured Output</th><th>Field Details</th><th>Accuracy</th><th>ms</th></tr>");
        for (var r : results) {
            // Structurer badges
            var badges = new StringBuilder();
            for (var s : r.structurersUsed) {
                badges.append(badge(s, s));
            }
            if (r.structurersUsed.isEmpty()) {
                badges.append("<span class='badge badge-fail'>none</span>");
            }

            // Field details
            var fieldsHtml = new StringBuilder();
            for (var f : r.fields.entrySet()) {
                var v = f.getValue();
                var cls = v.match ? "match" : "miss";
                var icon = v.match ? "✓" : "✗";
                fieldsHtml.append(String.format(
                        "<div class='field-row'><span class='%s'>%s</span> <b>%s</b>: " +
                        "<span class='%s'>%s</span> → %s " +
                        "<span class='badge badge-%s'>%s</span> " +
                        "<small>(%.0f%%)</small></div>",
                        cls, icon, f.getKey(),
                        cls, v.expected.isEmpty() ? "—" : v.expected,
                        v.actual.isEmpty() ? "<i>empty</i>" : v.actual,
                        badgeClass(v.structurerSource), v.structurerSource,
                        v.confidence * 100));
            }

            // Structured output with named labels
            var fieldLabels = Map.of(
                    "CTRY", "Country", "TWN_NM", "City", "CTRY_SUB_DVSN", "State/Province",
                    "STRT_NM", "Street", "BLDG_NB", "Building #", "BLDG_NM", "Building Name",
                    "PST_CD", "Postal Code", "ADR_LINE", "Address Line");
            var structuredAddr = new StringBuilder();
            var actuals = r.fields;
            for (var fieldOrder : List.of("CTRY", "TWN_NM", "CTRY_SUB_DVSN", "STRT_NM", "BLDG_NB", "BLDG_NM", "PST_CD", "ADR_LINE")) {
                if (actuals.containsKey(fieldOrder) && !actuals.get(fieldOrder).actual.isEmpty()) {
                    var label = fieldLabels.getOrDefault(fieldOrder, fieldOrder);
                    structuredAddr.append(String.format(
                            "<div><small style='color:#888'>%s:</small> <b>%s</b></div>",
                            label, actuals.get(fieldOrder).actual));
                }
            }
            var structuredText = structuredAddr.isEmpty() ? "<i>no output</i>" : structuredAddr.toString();

            var accBadge = r.accuracy == 1.0 ? "badge-ok" : "badge-fail";
            html.append(String.format(
                    "<tr><td><b>%s</b></td><td>%s</td><td><span class='badge badge-source'>%s</span></td>" +
                    "<td>%s</td>" +
                    "<td style='max-width:220px;word-wrap:break-word;font-size:11px'>%s</td>" +
                    "<td style='max-width:220px;font-size:12px;color:#1a1a2e'><b>%s</b></td>" +
                    "<td>%s</td>" +
                    "<td><span class='badge %s'>%.0f%%</span></td><td>%d</td></tr>",
                    r.fixtureId, r.country, r.source, badges, r.raw, structuredText, fieldsHtml,
                    accBadge, r.accuracy * 100, r.latencyMs));
        }
        html.append("</table>");
        html.append("</body></html>");

        Files.writeString(REPORT_PATH, html.toString());
        System.out.println("\n=== ACCURACY REPORT ===");
        System.out.printf("Mode: %s%n", structurerMode);
        System.out.printf("Total: %d fixtures, %.0f%% avg accuracy, %d perfect, %dms avg latency%n",
                total, avgAccuracy * 100, perfect, avgLatency);
        System.out.printf("Structurers: libpostal=%d, llm=%d, stub=%d%n", libpostalCount, llmCount, stubCount);
        System.out.println("Report: " + REPORT_PATH.toAbsolutePath());
    }

    private static String card(String value, String label) {
        return String.format("<div class='card'><div class='value'>%s</div><div class='label'>%s</div></div>", value, label);
    }

    private static String card(String value, String label, String cls) {
        return String.format("<div class='card'><div class='value'><span class='badge %s' style='font-size:24px;padding:4px 12px'>%s</span></div><div class='label'>%s</div></div>", cls, value, label);
    }

    private static String badge(String structurer, String text) {
        return String.format("<span class='badge badge-%s'>%s</span> ", badgeClass(structurer), text);
    }

    private static String badgeClass(String structurer) {
        return switch (structurer) {
            case "libpostal" -> "libpostal";
            case "llm" -> "llm";
            case "stub" -> "stub";
            default -> "source";
        };
    }
}

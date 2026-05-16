package com.jpmc.tfpm.address.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Shared accuracy test infrastructure for both fast smoke and full runs.
 * Uses {@link RestClient} with configurable connect/read timeouts
 * instead of {@link org.springframework.boot.test.web.client.TestRestTemplate}.
 */
abstract class AccuracyTestBase {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");

    /** Default read timeout per request — LLM calls can be slow. */
    private static final int DEFAULT_READ_TIMEOUT_MS = 60_000;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

    record FixtureResult(String id, String country, String source, String raw,
                         Map<String, FieldDetail> fields, String outcome,
                         String recommendation, List<String> reviewReasons,
                         double overallConfidence, List<String> sources,
                         ConsensusInfo consensus,
                         int expectedCount, int matchedCount,
                         double accuracy, long latencyMs) {}

    record FieldDetail(String expected, String actual, double confidence, boolean match, String mergeStrategy) {}


    record ConsensusInfo(int sourceCount, int agreements, int disagreements,
                         double overall, Map<String, Double> sourceWeights,
                         Map<String, FieldConsensusInfo> fields) {}

    record FieldConsensusInfo(boolean agreed, String consensusValue,
                               Map<String, String> sourceValues) {}

    /**
     * Build a RestClient pointing at the test server with generous timeouts.
     */
    static RestClient buildClient(int port) {
        return buildClient(port, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    static RestClient buildClient(int port, int connectTimeoutMs, int readTimeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);

        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(factory)
                .build();
    }

    // ========== Fixture loading ==========

    static List<Path> loadAllFixtures() throws IOException {
        if (!Files.exists(GOLDEN_DIR)) return List.of();
        try (var walk = Files.walk(GOLDEN_DIR)) {
            return walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    /** Sample 1 fixture per country directory (deterministic — picks the first). */
    static List<Path> loadSampledFixtures() throws IOException {
        if (!Files.exists(GOLDEN_DIR)) return List.of();
        var sampled = new ArrayList<Path>();
        try (var dirs = Files.list(GOLDEN_DIR)) {
            dirs.filter(Files::isDirectory).sorted().forEach(countryDir -> {
                try (var files = Files.list(countryDir)) {
                    files.filter(p -> p.toString().endsWith(".json"))
                            .sorted()
                            .findFirst()
                            .ifPresent(sampled::add);
                } catch (IOException ignored) {}
            });
        }
        return sampled;
    }

    /** Load ad-hoc addresses from a JSON array file (no expected_fields). */
    static List<Path> loadAdhocFixtures() throws IOException {
        var adhocDir = Path.of("src/test/resources/adhoc");
        if (!Files.exists(adhocDir)) return List.of();
        // Write each entry from the JSON array as a temp file for processFixture
        var tempPaths = new ArrayList<Path>();
        try (var files = Files.list(adhocDir)) {
            files.filter(p -> p.toString().endsWith(".json")).sorted().forEach(p -> {
                try {
                    var node = MAPPER.readTree(p.toFile());
                    if (node.isArray()) {
                        for (var entry : node) {
                            var tmp = Files.createTempFile("adhoc-", ".json");
                            MAPPER.writeValue(tmp.toFile(), entry);
                            tempPaths.add(tmp);
                        }
                    }
                } catch (IOException ignored) {}
            });
        }
        return tempPaths;
    }

    /** Callback for streaming results to disk as they complete. */
    @FunctionalInterface
    interface ResultSink {
        void accept(FixtureResult result) throws IOException;
    }

    // ========== Processing ==========

    static List<FixtureResult> processFixturesParallel(
            List<Path> fixtures, RestClient client, int parallelism) {
        return processFixturesParallel(fixtures, client, parallelism, null);
    }

    static List<FixtureResult> processFixturesParallel(
            List<Path> fixtures, RestClient client, int parallelism, ResultSink sink) {
        var results = Collections.synchronizedList(new ArrayList<FixtureResult>());
        var executor = Executors.newFixedThreadPool(
                Math.min(parallelism, fixtures.size()));

        var futures = new ArrayList<Future<?>>();
        for (var path : fixtures) {
            futures.add(executor.submit(() -> {
                try {
                    var fixture = MAPPER.readTree(path.toFile());
                    processFixture(fixture, client, results, sink);
                } catch (IOException e) {
                    System.err.println("Failed to read fixture " + path + ": " + e.getMessage());
                }
            }));
        }

        long totalTimeoutMs = Math.min(fixtures.size() * 60_000L, 600_000L);
        long deadline = System.currentTimeMillis() + totalTimeoutMs;
        for (var future : futures) {
            try {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining > 0) {
                    future.get(remaining, TimeUnit.MILLISECONDS);
                } else {
                    future.cancel(true);
                }
            } catch (TimeoutException e) {
                future.cancel(true);
            } catch (Exception ignored) {}
        }

        executor.shutdownNow();
        return results;
    }

    static List<FixtureResult> processFixturesSequential(
            List<Path> fixtures, RestClient client) {
        return processFixturesSequential(fixtures, client, null);
    }

    static List<FixtureResult> processFixturesSequential(
            List<Path> fixtures, RestClient client, ResultSink sink) {
        var results = new ArrayList<FixtureResult>();
        for (var path : fixtures) {
            try {
                var fixture = MAPPER.readTree(path.toFile());
                processFixture(fixture, client, results, sink);
            } catch (IOException e) {
                System.err.println("Failed to read fixture " + path + ": " + e.getMessage());
            }
        }
        return results;
    }

    /**
     * Process fixtures in streaming mode — writes each result to the CSV sink
     * immediately. Does NOT accumulate results in memory. Returns count only.
     */
    static int processFixturesStreaming(
            List<Path> fixtures, RestClient client, int parallelism, CsvReportWriter csvWriter) {
        var count = new java.util.concurrent.atomic.AtomicInteger(0);
        var executor = Executors.newFixedThreadPool(
                Math.min(parallelism, fixtures.size()));

        var futures = new ArrayList<Future<?>>();
        for (var path : fixtures) {
            futures.add(executor.submit(() -> {
                try {
                    var fixture = MAPPER.readTree(path.toFile());
                    var result = processFixtureSingle(fixture, client);
                    if (result != null) {
                        csvWriter.writeRow(result);
                        count.incrementAndGet();
                        if (count.get() % 50 == 0) {
                            System.out.printf("  ... %d fixtures processed%n", count.get());
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Failed: " + path + ": " + e.getMessage());
                }
            }));
        }

        long totalTimeoutMs = Math.min(fixtures.size() * 60_000L, 1_800_000L);
        long deadline = System.currentTimeMillis() + totalTimeoutMs;
        for (var future : futures) {
            try {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining > 0) future.get(remaining, TimeUnit.MILLISECONDS);
                else future.cancel(true);
            } catch (TimeoutException e) { future.cancel(true); }
            catch (Exception ignored) {}
        }

        executor.shutdownNow();
        return count.get();
    }

    private static void processFixture(JsonNode fixture, RestClient client,
                                        List<FixtureResult> results, ResultSink sink) {
        var result = processFixtureSingle(fixture, client);
        if (result != null) {
            results.add(result);
            if (sink != null) {
                try { sink.accept(result); } catch (IOException e) {
                    System.err.println("Sink write failed: " + e.getMessage());
                }
            }
        }
    }

    private static FixtureResult processFixtureSingle(JsonNode fixture, RestClient client) {
        var id = fixture.path("fixture_id").asText("?");
        var country = fixture.path("country").asText("");
        var source = fixture.path("source").asText("");
        var raw = fixture.path("raw").asText("");
        var hint = fixture.path("country_hint").asText("");
        var locale = fixture.path("locale").asText("");
        var expected = fixture.path("expected_fields");

        var body = Map.of("rawAddress", raw, "countryHint", hint, "locale", locale);
        var start = System.currentTimeMillis();

        JsonNode respBody;
        try {
            respBody = client.post()
                    .uri("/api/v1/enrich")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Correlation-Id", "e2e-" + id)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        throw new RuntimeException("HTTP " + resp.getStatusCode() + " for " + id);
                    })
                    .body(JsonNode.class);
        } catch (Exception e) {
            System.err.println("API call failed for " + id + ": " + e.getMessage());
            return null;
        }

        var latencyMs = System.currentTimeMillis() - start;
        if (respBody == null) return null;

        var outcome = respBody.path("outcome").asText("");
        var recommendation = respBody.path("recommendation").asText("AutoApproved");
        var overallConf = respBody.path("overallConfidence").asDouble(0);
        var respFields = respBody.path("fields");
        var sourcesNode = respBody.path("sources");
        var sources = new ArrayList<String>();
        if (sourcesNode.isArray()) {
            sourcesNode.forEach(n -> sources.add(n.asText()));
        }
        var reviewReasonsNode = respBody.path("reviewReasons");
        var reviewReasons = new ArrayList<String>();
        if (reviewReasonsNode.isArray()) {
            reviewReasonsNode.forEach(n -> reviewReasons.add(n.path("rule").asText() + ": " + n.path("details").asText()));
        }

        // Parse consensus
        ConsensusInfo consensus = null;
        var consensusNode = respBody.path("consensus");
        if (!consensusNode.isMissingNode() && !consensusNode.isNull()) {
            var cFields = new LinkedHashMap<String, FieldConsensusInfo>();
            var cFieldsNode = consensusNode.path("fields");
            cFieldsNode.fieldNames().forEachRemaining(fn -> {
                var fc = cFieldsNode.path(fn);
                var svMap = new LinkedHashMap<String, String>();
                fc.path("sourceValues").fieldNames().forEachRemaining(src ->
                        svMap.put(src, fc.path("sourceValues").path(src).asText("")));
                cFields.put(fn, new FieldConsensusInfo(
                        fc.path("agreed").asBoolean(true),
                        fc.path("consensusValue").asText(""),
                        svMap));
            });
            var weightsMap = new LinkedHashMap<String, Double>();
            var weightsNode = consensusNode.path("sourceWeights");
            weightsNode.fieldNames().forEachRemaining(src ->
                    weightsMap.put(src, weightsNode.path(src).asDouble(1.0)));

            consensus = new ConsensusInfo(
                    consensusNode.path("sourceCount").asInt(0),
                    consensusNode.path("agreementCount").asInt(0),
                    consensusNode.path("disagreementCount").asInt(0),
                    consensusNode.path("overallConsensus").asDouble(0),
                    weightsMap, cFields);
        }

        var fields = new LinkedHashMap<String, FieldDetail>();
        int expCount = 0, matched = 0;

        boolean hasExpected = expected != null && !expected.isMissingNode() && expected.fieldNames().hasNext();

        if (hasExpected) {
            // Golden mode: compare expected vs actual
            var it = expected.fieldNames();
            while (it.hasNext()) {
                var fn = it.next();
                var node = expected.path(fn);
                var expVal = node.isObject() ? node.path("value").asText("") : node.asText("");
                if (expVal.isEmpty()) continue;
                expCount++;

                var actualNode = respFields.path(fn);
                var actVal = actualNode.path("value").asText("");
                var actConf = actualNode.path("confidence").asDouble(0);
                var strategy = actualNode.path("mergeStrategy").asText("");
                var match = expVal.equalsIgnoreCase(actVal);
                if (match) matched++;
                fields.put(fn, new FieldDetail(expVal, actVal, actConf, match, strategy));
            }
        } else {
            // Output-only mode: just capture all actual fields
            respFields.fieldNames().forEachRemaining(fn -> {
                var actualNode = respFields.path(fn);
                var actVal = actualNode.path("value").asText("");
                var actConf = actualNode.path("confidence").asDouble(0);
                var strategy = actualNode.path("mergeStrategy").asText("");
                if (!actVal.isEmpty()) {
                    fields.put(fn, new FieldDetail("", actVal, actConf, false, strategy));
                }
            });
        }

        double acc = expCount > 0 ? (double) matched / expCount : -1;
        return new FixtureResult(id, country, source, raw, fields, outcome,
                recommendation, reviewReasons,
                overallConf, sources, consensus, expCount, matched, acc, latencyMs);
    }

    // ========== Report generation ==========

    static void generateReport(List<FixtureResult> results, Path reportPath, String mode) throws IOException {
        if (results.isEmpty()) return;

        int total = results.size();
        boolean hasGolden = results.stream().anyMatch(r -> r.accuracy >= 0);
        long perfect = results.stream().filter(r -> r.accuracy == 1.0).count();
        var scoredResults = results.stream().filter(r -> r.accuracy >= 0).toList();
        double avgAcc = scoredResults.isEmpty() ? -1 : scoredResults.stream().mapToDouble(r -> r.accuracy).average().orElse(0);
        long avgMs = (long) results.stream().mapToLong(r -> r.latencyMs).average().orElse(0);
        long successCount = results.stream().filter(r -> "SUCCESS".equals(r.outcome)).count();
        long reviewCount = results.stream().filter(r -> "REQUIRES_REVIEW".equals(r.outcome)).count();
        long unstructurable = results.stream().filter(r -> "UNSTRUCTURABLE".equals(r.outcome)).count();
        long consensusCount = results.stream().filter(r -> r.consensus != null).count();
        long disagreementCount = results.stream().filter(r -> r.consensus != null && r.consensus.disagreements > 0).count();

        var html = new StringBuilder();
        html.append("""
            <!DOCTYPE html><html><head><meta charset="UTF-8">
            <title>TFPM Address Enrichment — E2E Accuracy Report</title>
            <style>
            * { box-sizing: border-box; }
            body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 0; padding: 20px; background: #f8f9fa; color: #1a1a2e; }
            h1 { margin: 0 0 5px; font-size: 24px; }
            h2 { color: #16213e; margin: 30px 0 10px; font-size: 18px; border-bottom: 2px solid #e0e0e0; padding-bottom: 5px; }
            .subtitle { color: #666; font-size: 13px; margin-bottom: 15px; }
            .cards { display: flex; flex-wrap: wrap; gap: 10px; margin: 15px 0; }
            .card { background: white; padding: 16px 20px; border-radius: 10px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); text-align: center; min-width: 120px; }
            .card .v { font-size: 28px; font-weight: 700; }
            .card .l { font-size: 11px; color: #888; margin-top: 3px; }
            table { border-collapse: collapse; width: 100%%; background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 4px rgba(0,0,0,0.08); margin: 10px 0; }
            th { background: #1a1a2e; color: white; padding: 10px 12px; font-size: 12px; text-align: left; text-transform: uppercase; letter-spacing: 0.5px; }
            td { padding: 8px 12px; border-bottom: 1px solid #f0f0f0; font-size: 13px; vertical-align: top; }
            tr:hover { background: #f8f9ff; }
            .badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 10px; font-weight: 600; }
            .b-ok { background: #d1fae5; color: #065f46; }
            .b-fail { background: #fee2e2; color: #991b1b; }
            .b-review { background: #fef3c7; color: #92400e; }
            .b-src { background: #f3f4f6; color: #374151; }
            .match { color: #059669; }
            .miss { color: #dc2626; }
            .expected { background: #f0fdf4; padding: 5px 10px; border-radius: 6px; border-left: 3px solid #22c55e; margin: 2px 0; font-size: 12px; }
            .actual { background: #eff6ff; padding: 5px 10px; border-radius: 6px; border-left: 3px solid #3b82f6; margin: 2px 0; font-size: 12px; }
            .field-label { color: #888; font-size: 11px; }
            .raw-addr { font-size: 11px; color: #555; max-width: 250px; word-wrap: break-word; font-style: italic; }
            .mode-bar { padding: 12px 20px; border-radius: 8px; margin: 10px 0; font-size: 13px; color: white; background: #059669; }
            </style></head><body>
            """);

        html.append("<h1>Address Enrichment — E2E Accuracy Report</h1>");
        html.append("<div class='subtitle'>").append(LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(" · ").append(mode).append("</div>");

        html.append("<div class='mode-bar'>Live Spring Boot app → POST /api/v1/enrich → real pipeline (config from application-local.yml)</div>");

        html.append("<div class='cards'>");
        html.append(card(total, "Fixtures"));
        if (hasGolden) {
            html.append(card(String.format("%.0f%%", avgAcc * 100), "Accuracy"));
            html.append(card(perfect, "Perfect"));
        }
        html.append(card(avgMs + "ms", "Avg Latency"));
        html.append(card(successCount, "SUCCESS", "b-ok")).append(card(reviewCount, "REVIEW", "b-review"));
        html.append(card(unstructurable, "UNSTRUCTURABLE", "b-fail"));
        if (consensusCount > 0) {
            html.append(card(consensusCount, "Consensus Runs", "b-src"));
            html.append(card(disagreementCount, "Disagreements", disagreementCount > 0 ? "b-review" : "b-ok"));
        }
        html.append("</div>");

        // Per-country
        html.append("<h2>Per-Country</h2><table><tr><th>Country</th><th>Fixtures</th>");
        if (hasGolden) html.append("<th>Accuracy</th><th>Perfect</th>");
        html.append("<th>Avg Latency</th><th>Outcomes</th><th>Consensus</th></tr>");
        var byCountry = new TreeMap<String, List<FixtureResult>>();
        results.forEach(r -> byCountry.computeIfAbsent(r.country, k -> new ArrayList<>()).add(r));
        for (var e : byCountry.entrySet()) {
            var cr = e.getValue();
            var cl = (long) cr.stream().mapToLong(r -> r.latencyMs).average().orElse(0);
            var ok = cr.stream().filter(r -> "SUCCESS".equals(r.outcome)).count();
            var rev = cr.stream().filter(r -> "REQUIRES_REVIEW".equals(r.outcome)).count();
            var cDisagree = cr.stream().filter(r -> r.consensus != null && r.consensus.disagreements > 0).count();
            var cTotal = cr.stream().filter(r -> r.consensus != null).count();
            html.append(String.format("<tr><td><b>%s</b></td><td>%d</td>", e.getKey(), cr.size()));
            if (hasGolden) {
                var scored = cr.stream().filter(r -> r.accuracy >= 0).toList();
                var ca = scored.isEmpty() ? 0 : scored.stream().mapToDouble(r -> r.accuracy).average().orElse(0);
                var cp = cr.stream().filter(r -> r.accuracy == 1.0).count();
                html.append(String.format("<td>%.0f%%</td><td>%d</td>", ca * 100, cp));
            }
            html.append(String.format("<td>%dms</td>" +
                    "<td><span class='badge b-ok'>%d</span> <span class='badge b-review'>%d</span></td>" +
                    "<td>%s</td></tr>",
                    cl, ok, rev,
                    cTotal > 0 ? String.format("<span class='badge %s'>%d✗/%d</span>",
                            cDisagree > 0 ? "b-review" : "b-ok", cDisagree, cTotal) : "—"));
        }
        html.append("</table>");

        // Detail
        html.append("<h2>Detailed Results</h2><table><tr><th>ID</th><th>Country</th><th>Sources</th><th>Outcome</th><th>Raw</th>");
        if (hasGolden) html.append("<th>Expected</th>");
        html.append("<th>Structured Output</th><th>Consensus</th>");
        if (hasGolden) html.append("<th>Accuracy</th>");
        html.append("<th>ms</th></tr>");
        for (var r : results) {
            var outBadge = switch (r.outcome) {
                case "SUCCESS" -> "b-ok";
                case "REQUIRES_REVIEW" -> "b-review";
                default -> "b-fail";
            };

            var expHtml = new StringBuilder();
            var actHtml = new StringBuilder();
            boolean isGoldenRow = r.accuracy >= 0;
            for (var f : r.fields.entrySet()) {
                var d = f.getValue();
                if (isGoldenRow) {
                    // Golden mode: show expected + actual with match indicator
                    if (d.expected.isEmpty()) continue;
                    expHtml.append("<div class='expected'><span class='field-label'>")
                            .append(label(f.getKey())).append(":</span> ").append(d.expected).append("</div>");
                    var icon = d.match ? "<span class='match'>✓</span>" : "<span class='miss'>✗</span>";
                    var stratBadge = strategyBadge(d.mergeStrategy);
                    actHtml.append("<div class='actual'>").append(icon)
                            .append(" <span class='field-label'>").append(label(f.getKey())).append(":</span> <b>")
                            .append(d.actual.isEmpty() ? "<i>empty</i>" : d.actual)
                            .append("</b> <small>(").append(String.format("%.0f%%", d.confidence * 100))
                            .append(")</small> ").append(stratBadge).append("</div>");
                } else {
                    // Output-only: just show actual fields
                    var stratBadge = strategyBadge(d.mergeStrategy);
                    actHtml.append("<div class='actual'>")
                            .append("<span class='field-label'>").append(label(f.getKey())).append(":</span> <b>")
                            .append(d.actual.isEmpty() ? "<i>empty</i>" : d.actual)
                            .append("</b> <small>(").append(String.format("%.0f%%", d.confidence * 100))
                            .append(")</small> ").append(stratBadge).append("</div>");
                }
            }

            var sourceBadges = new StringBuilder();
            for (var s : r.sources) {
                sourceBadges.append("<span class='badge b-src'>").append(s).append("</span> ");
            }
            if (r.sources.isEmpty()) {
                sourceBadges.append("<span class='badge b-fail'>none</span>");
            }

            // Consensus column
            var consensusHtml = new StringBuilder();
            if (r.consensus != null) {
                var c = r.consensus;
                var consBadge = c.disagreements == 0 ? "b-ok" : "b-review";
                consensusHtml.append(String.format(
                        "<div style='margin-bottom:4px'><span class='badge %s'>%d✓ %d✗</span> <small>%.0f%%</small></div>",
                        consBadge, c.agreements, c.disagreements, c.overall * 100));
                // Show source weights
                if (!c.sourceWeights.isEmpty()) {
                    consensusHtml.append("<div style='font-size:10px;color:#888;margin-bottom:3px'>Weights: ");
                    c.sourceWeights.forEach((src, w) ->
                            consensusHtml.append(String.format("<span class='badge b-src'>%s</span> %.1f ", src, w)));
                    consensusHtml.append("</div>");
                }
                for (var fc : c.fields.entrySet()) {
                    var fci = fc.getValue();
                    var icon = fci.agreed ? "<span class='match'>✓</span>" : "<span class='miss'>⚠</span>";
                    consensusHtml.append("<div style='font-size:11px;margin:1px 0'>")
                            .append(icon).append(" <b>").append(label(fc.getKey())).append("</b>");
                    if (!fci.agreed) {
                        consensusHtml.append("<div style='margin-left:12px;font-size:10px;color:#666'>");
                        for (var sv : fci.sourceValues.entrySet()) {
                            var weight = c.sourceWeights.getOrDefault(sv.getKey(), 1.0);
                            var weightLabel = weight < 1.0 ? String.format(" <small>(%.1f)</small>", weight) : "";
                            consensusHtml.append("<span class='badge b-src'>").append(sv.getKey())
                                    .append("</span>").append(weightLabel).append(" ").append(sv.getValue()).append("<br>");
                        }
                        consensusHtml.append("</div>");
                    }
                    consensusHtml.append("</div>");
                }
            } else {
                consensusHtml.append("<span style='color:#aaa;font-size:11px'>single source</span>");
            }

            html.append(String.format("<tr><td><b>%s</b></td><td>%s</td>" +
                    "<td>%s</td>" +
                    "<td><span class='badge %s'>%s</span></td>" +
                    "<td class='raw-addr'>%s</td>",
                    r.id, r.country, sourceBadges,
                    outBadge, r.outcome, r.raw));
            if (hasGolden) html.append("<td>").append(expHtml).append("</td>");
            html.append("<td>").append(actHtml).append("</td>");
            html.append("<td>").append(consensusHtml).append("</td>");
            if (hasGolden) {
                var accBadge = r.accuracy == 1.0 ? "b-ok" : (r.accuracy < 0 ? "b-src" : "b-fail");
                var accText = r.accuracy < 0 ? "n/a" : String.format("%.0f%%", r.accuracy * 100);
                html.append(String.format("<td><span class='badge %s'>%s</span></td>", accBadge, accText));
            }
            html.append(String.format("<td>%d</td></tr>", r.latencyMs));
        }
        html.append("</table></body></html>");

        Files.writeString(reportPath, html.toString());
        System.out.printf("%n=== E2E ACCURACY REPORT (%s) ===%nTotal: %d, %.0f%% accuracy, %d perfect, %dms avg%n",
                mode, total, avgAcc * 100, perfect, avgMs);
        System.out.printf("Outcomes: SUCCESS=%d, REVIEW=%d, UNSTRUCTURABLE=%d%n",
                successCount, reviewCount, unstructurable);
        System.out.println("Report: " + reportPath.toAbsolutePath());
    }

    private static String card(Object v, String l) {
        return "<div class='card'><div class='v'>" + v + "</div><div class='l'>" + l + "</div></div>";
    }
    private static String card(Object v, String l, String cls) {
        return "<div class='card'><div class='v'><span class='badge " + cls + "' style='font-size:20px;padding:4px 14px'>" + v + "</span></div><div class='l'>" + l + "</div></div>";
    }
    private static String strategyBadge(String strategy) {
        if (strategy == null || strategy.isEmpty()) return "";
        return switch (strategy) {
            case "CONSENSUS" -> "<span class='badge b-ok' style='font-size:8px'>consensus</span>";
            case "HIGHEST_CONFIDENCE" -> "<span class='badge b-review' style='font-size:8px'>best-conf</span>";
            case "SINGLE_SOURCE" -> "<span class='badge b-src' style='font-size:8px'>single</span>";
            default -> "";
        };
    }

    private static String label(String f) {
        return switch (f) {
            case "CTRY" -> "Country"; case "TWN_NM" -> "City"; case "CTRY_SUB_DVSN" -> "State";
            case "STRT_NM" -> "Street"; case "BLDG_NB" -> "Bldg #"; case "BLDG_NM" -> "Bldg";
            case "PST_CD" -> "Postal"; case "ADR_LINE" -> "Line"; default -> f;
        };
    }
}

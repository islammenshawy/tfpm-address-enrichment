package com.jpmc.tfpm.address.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.CascadeResult;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.ConsensusResult;
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
 * End-to-end accuracy test. Auto-detects available structurers (libpostal sidecar,
 * LLM providers via env vars). Generates styled HTML report with consensus analysis.
 */
@DisplayName("End-to-End Accuracy Report")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndAccuracyIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");
    private static final Path REPORT_PATH = Path.of("target/accuracy-report.html");

    private static final List<FixtureResult> results = Collections.synchronizedList(new ArrayList<>());
    private static String structurerMode = "unknown";

    record FixtureResult(String fixtureId, String country, String source, String raw,
                         Map<String, FieldDetail> fields, boolean cascadeSuccess,
                         List<String> structurersUsed, ConsensusResult consensus,
                         int expectedCount, int matchedCount, double accuracy, long latencyMs) {}

    record FieldDetail(String expected, String actual, double confidence,
                       String sourceStructurer, boolean agreed, Map<String, String> alternatives,
                       boolean match) {}

    @Test
    @Order(1)
    void run_cascade() throws IOException {
        if (!Files.exists(GOLDEN_DIR)) return;

        var structurers = new ArrayList<AddressStructurer>();
        var calibrators = new ArrayList<ConfidenceCalibrator>();
        var labels = new ArrayList<String>();
        var objectMapper = new ObjectMapper();

        // Auto-detect libpostal
        if (isReachable("localhost", 50051)) {
            var ch = io.grpc.ManagedChannelBuilder.forTarget("localhost:50051").usePlaintext().build();
            structurers.add(new com.jpmc.tfpm.address.adapter.libpostal.LibpostalAddressStructurer(ch, 2000));
            calibrators.add(new com.jpmc.tfpm.address.adapter.libpostal.LibpostalConfidenceCalibrator());
            labels.add("libpostal");
        }

        // Auto-detect LLM providers from env — same vars the app reads
        addLlmFromEnv(structurers, calibrators, labels, objectMapper,
                "GLM_API_KEY", "GLM_ENDPOINT", "GLM_MODEL",
                "glm", "https://api.z.ai/api/coding/paas/v4", "glm-5.1", false);
        addLlmFromEnv(structurers, calibrators, labels, objectMapper,
                "AZURE_OPENAI_API_KEY", "AZURE_OPENAI_ENDPOINT", "AZURE_OPENAI_DEPLOYMENT_NAME",
                "azure-gpt", "https://azuretest123.openai.azure.com/openai/deployments/gpt-4.1-mini",
                "gpt-4.1-mini", true);

        if (structurers.isEmpty()) {
            structurers.add(new com.jpmc.tfpm.address.app.cascade.StubAddressStructurer());
            calibrators.add(new IdentityConfidenceCalibrator("stub"));
            labels.add("stub");
        }

        structurerMode = String.join(" → ", labels);
        var merger = new FieldMerger(calibrators);
        var orchestrator = new CascadeOrchestrator(structurers, merger, CountryRouter.noOp(),
                0.99, 60000L, new SimpleMeterRegistry());

        Files.walk(GOLDEN_DIR).filter(p -> p.toString().endsWith(".json")).sorted().forEach(path -> {
            try { processFixture(MAPPER.readTree(path.toFile()), orchestrator); }
            catch (IOException ignored) {}
        });
        assertThat(results).isNotEmpty();
    }

    private void addLlmFromEnv(List<AddressStructurer> structurers, List<ConfidenceCalibrator> calibrators,
                                List<String> labels, ObjectMapper om,
                                String keyEnv, String epEnv, String modelEnv,
                                String name, String defaultEp, String defaultModel, boolean isAzure) {
        var key = System.getenv(keyEnv);
        if (key == null || key.isBlank()) return;
        try {
            var ep = System.getenv(epEnv);
            if (ep == null || ep.isBlank()) ep = defaultEp;
            var model = System.getenv(modelEnv);
            if (model == null || model.isBlank()) model = defaultModel;

            var wcb = org.springframework.web.reactive.function.client.WebClient.builder().baseUrl(ep);
            if (isAzure) {
                wcb.defaultHeader("api-key", key);
                var ver = Optional.ofNullable(System.getenv("AZURE_OPENAI_API_VERSION")).orElse("2024-02-15-preview");
                wcb.filter((req, next) -> next.exchange(
                        org.springframework.web.reactive.function.client.ClientRequest.from(req)
                                .url(org.springframework.web.util.UriComponentsBuilder.fromUri(req.url())
                                        .queryParam("api-version", ver).build().toUri()).build()));
            }
            var meta = new com.jpmc.tfpm.address.domain.LlmModelClient.LlmModelMetadata(
                    isAzure ? "azure-openai" : "openai-compatible", model, false, 0, 0, Duration.ofSeconds(30));
            var cbCfg = io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                    .failureRateThreshold(100).slidingWindowSize(200).minimumNumberOfCalls(200).build();
            var cb = io.github.resilience4j.circuitbreaker.CircuitBreaker.of(name, cbCfg);
            cb.transitionToDisabledState();
            var client = new com.jpmc.tfpm.address.adapter.llm.client.OpenAiCompatibleLlmClient(
                    name, meta, wcb.build(), om, isAzure ? "" : key, cb, 2);
            var prompt = new com.jpmc.tfpm.address.adapter.llm.PromptTemplateLoader(
                    new org.springframework.core.io.ClassPathResource("prompts/address-structuring.json"), om);
            structurers.add(new com.jpmc.tfpm.address.adapter.llm.LlmAddressStructurer(name, client, prompt, om,
                    EnumSet.of(AddressField.CTRY, AddressField.TWN_NM, AddressField.PST_CD,
                            AddressField.CTRY_SUB_DVSN, AddressField.STRT_NM, AddressField.BLDG_NB, AddressField.BLDG_NM)));
            calibrators.add(new IdentityConfidenceCalibrator(name));
            labels.add(name + " (" + model + ")");
            System.out.println("Enabled: " + name + " → " + model);
        } catch (Exception e) { System.err.println(name + " init failed: " + e.getMessage()); }
    }

    private void processFixture(JsonNode fixture, CascadeOrchestrator orchestrator) {
        var id = fixture.path("fixture_id").asText("?");
        var country = fixture.path("country").asText("");
        var source = fixture.path("source").asText("");
        var raw = fixture.path("raw").asText("");
        var hint = fixture.path("country_hint").asText("");
        var locale = fixture.path("locale").asText("");
        var expected = fixture.path("expected_fields");

        com.jpmc.tfpm.address.domain.RawAddress addr;
        try { addr = new com.jpmc.tfpm.address.domain.RawAddress(raw, hint, locale); }
        catch (Exception e) { addr = com.jpmc.tfpm.address.domain.RawAddress.of(raw); }

        var start = Instant.now();
        var cr = orchestrator.orchestrate(addr, "e2e-" + id);
        var ms = Duration.between(start, Instant.now()).toMillis();

        var cascade = cr.isSuccess() ? ((Result.Success<CascadeResult>) cr).value() : null;
        var structured = cascade != null ? cascade.structuredAddress() : StructuredAddress.empty();
        var consensus = cascade != null ? cascade.consensus() : null;
        var usedStructurers = cascade != null
                ? cascade.structurerTrace().stream().filter(t -> !t.fields().isEmpty())
                    .map(StructuringResult::structurerName).distinct().toList()
                : List.<String>of();

        // Field-source map
        var fieldSource = new HashMap<AddressField, String>();
        if (cascade != null) {
            for (var t : cascade.structurerTrace()) {
                for (var f : t.fields().keySet()) {
                    var mv = structured.get(f);
                    if (mv.isPresent() && t.fields().get(f) != null
                            && mv.get().value().equals(t.fields().get(f).value()))
                        fieldSource.put(f, t.structurerName());
                }
            }
        }

        var fields = new LinkedHashMap<String, FieldDetail>();
        int expCount = 0, matched = 0;
        var it = expected.fieldNames();
        while (it.hasNext()) {
            var fn = it.next();
            var node = expected.path(fn);
            var expVal = node.isObject() ? node.path("value").asText("") : node.asText("");
            if (expVal.isEmpty()) continue;
            expCount++;
            try {
                var field = AddressField.valueOf(fn);
                var actual = structured.get(field);
                var actVal = actual.map(FieldValue::value).orElse("");
                var conf = actual.map(FieldValue::confidence).orElse(0.0);
                var src = fieldSource.getOrDefault(field, "—");
                boolean match = expVal.equalsIgnoreCase(actVal);
                if (match) matched++;

                boolean agreed = true;
                var alts = Map.<String, String>of();
                if (consensus != null && consensus.fieldConsensus().containsKey(field)) {
                    var fc = consensus.fieldConsensus().get(field);
                    agreed = fc.agreed();
                    alts = fc.alternatives();
                }
                fields.put(fn, new FieldDetail(expVal, actVal, conf, src, agreed, alts, match));
            } catch (IllegalArgumentException ignored) {}
        }

        double acc = expCount > 0 ? (double) matched / expCount : 0;
        results.add(new FixtureResult(id, country, source, raw, fields, cr.isSuccess(),
                usedStructurers, consensus, expCount, matched, acc, ms));
    }

    @Test
    @Order(2)
    void generate_report() throws IOException {
        if (results.isEmpty()) return;
        int total = results.size();
        long perfect = results.stream().filter(r -> r.accuracy == 1.0).count();
        double avgAcc = results.stream().mapToDouble(r -> r.accuracy).average().orElse(0);
        long avgMs = (long) results.stream().mapToLong(r -> r.latencyMs).average().orElse(0);
        long consensusOk = results.stream().filter(r -> r.consensus != null && !r.consensus.hasDisagreements()).count();
        long consensusFlagged = results.stream().filter(r -> r.consensus != null && r.consensus.hasDisagreements()).count();

        var structurerCounts = results.stream().flatMap(r -> r.structurersUsed.stream())
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

        var html = new StringBuilder();
        html.append("""
            <!DOCTYPE html><html><head><meta charset="UTF-8">
            <title>TFPM Address Enrichment — Accuracy Report</title>
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
            .b-lib { background: #dbeafe; color: #1e40af; }
            .b-glm { background: #fef3c7; color: #92400e; }
            .b-gpt { background: #ede9fe; color: #5b21b6; }
            .b-stub { background: #fee2e2; color: #991b1b; }
            .b-ok { background: #d1fae5; color: #065f46; }
            .b-fail { background: #fee2e2; color: #991b1b; }
            .b-src { background: #f3f4f6; color: #374151; }
            .b-agree { background: #d1fae5; color: #065f46; }
            .b-disagree { background: #fef3c7; color: #92400e; border: 1px solid #f59e0b; }
            .match { color: #059669; }
            .miss { color: #dc2626; }
            .field-label { color: #888; font-size: 11px; }
            .field-val { font-weight: 600; }
            .expected { background: #f0fdf4; padding: 6px 10px; border-radius: 6px; border-left: 3px solid #22c55e; margin: 2px 0; font-size: 12px; }
            .actual { background: #eff6ff; padding: 6px 10px; border-radius: 6px; border-left: 3px solid #3b82f6; margin: 2px 0; font-size: 12px; }
            .consensus-flag { background: #fff7ed; padding: 6px 10px; border-radius: 6px; border-left: 3px solid #f59e0b; margin: 4px 0; font-size: 11px; }
            .mode-bar { padding: 12px 20px; border-radius: 8px; margin: 10px 0; font-size: 13px; color: white; }
            .raw-addr { font-size: 11px; color: #555; max-width: 250px; word-wrap: break-word; font-style: italic; }
            </style></head><body>
            """);

        html.append("<h1>Address Enrichment — Accuracy Report</h1>");
        html.append("<div class='subtitle'>").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(" · ").append(structurerMode).append("</div>");

        // Mode bar
        var modeColor = structurerMode.contains("stub") ? "#dc2626" : "#059669";
        html.append("<div class='mode-bar' style='background:").append(modeColor).append("'>")
                .append("Pipeline: <b>").append(structurerMode).append("</b>");
        if (structurerMode.contains("stub")) html.append(" — ⚠ Stub mode, results are synthetic");
        html.append("</div>");

        // Summary cards
        html.append("<div class='cards'>");
        html.append(card(total, "Fixtures")).append(card(String.format("%.0f%%", avgAcc * 100), "Accuracy"))
                .append(card(perfect, "Perfect")).append(card(avgMs + "ms", "Avg Latency"));
        structurerCounts.forEach((s, c) -> html.append(card(c, s, badgeCls(s))));
        if (consensusOk + consensusFlagged > 0) {
            html.append(card(consensusOk, "Consensus ✓", "b-agree"));
            html.append(card(consensusFlagged, "Flagged ⚠", "b-disagree"));
        }
        html.append("</div>");

        // Per-country
        html.append("<h2>Per-Country Summary</h2><table><tr><th>Country</th><th>Fixtures</th><th>Accuracy</th><th>Perfect</th><th>Consensus</th><th>Sources</th><th>Latency</th></tr>");
        var byCountry = new TreeMap<String, List<FixtureResult>>();
        results.forEach(r -> byCountry.computeIfAbsent(r.country, k -> new ArrayList<>()).add(r));
        for (var e : byCountry.entrySet()) {
            var cr = e.getValue();
            var ca = cr.stream().mapToDouble(r -> r.accuracy).average().orElse(0);
            var cp = cr.stream().filter(r -> r.accuracy == 1.0).count();
            var cl = (long) cr.stream().mapToLong(r -> r.latencyMs).average().orElse(0);
            var cc = cr.stream().filter(r -> r.consensus != null && !r.consensus.hasDisagreements()).count();
            var cf = cr.stream().filter(r -> r.consensus != null && r.consensus.hasDisagreements()).count();
            var srcs = cr.stream().flatMap(r -> r.structurersUsed.stream())
                    .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
            var srcBadges = new StringBuilder();
            srcs.forEach((s, c) -> srcBadges.append("<span class='badge ").append(badgeCls(s)).append("'>")
                    .append(s).append(":").append(c).append("</span> "));
            var consensusHtml = cc + cf > 0
                    ? "<span class='badge b-agree'>✓ " + cc + "</span> <span class='badge b-disagree'>⚠ " + cf + "</span>"
                    : "—";
            html.append(String.format("<tr><td><b>%s</b></td><td>%d</td><td>%.0f%%</td><td>%d</td><td>%s</td><td>%s</td><td>%dms</td></tr>",
                    e.getKey(), cr.size(), ca * 100, cp, consensusHtml, srcBadges, cl));
        }
        html.append("</table>");

        // Detail table
        html.append("<h2>Detailed Results</h2><table><tr><th>ID</th><th>Country</th><th>Sources</th><th>Consensus</th><th>Raw Input</th><th>Expected</th><th>Actual Output</th><th>Accuracy</th></tr>");
        for (var r : results) {
            var badges = new StringBuilder();
            r.structurersUsed.forEach(s -> badges.append("<span class='badge ").append(badgeCls(s)).append("'>").append(s).append("</span> "));
            if (r.structurersUsed.isEmpty()) badges.append("<span class='badge b-fail'>none</span>");

            // Consensus cell
            var consHtml = "—";
            if (r.consensus != null) {
                if (r.consensus.hasDisagreements()) {
                    consHtml = "<span class='badge b-disagree'>⚠ " + r.consensus.disagreementCount() + " disagreement(s)</span>";
                    for (var ff : r.consensus.flaggedFields()) {
                        var fc = r.consensus.fieldConsensus().get(ff);
                        if (fc != null && !fc.alternatives().isEmpty()) {
                            consHtml += "<div class='consensus-flag'><b>" + ff + "</b>: ";
                            for (var alt : fc.alternatives().entrySet())
                                consHtml += "<span class='badge " + badgeCls(alt.getKey()) + "'>" + alt.getKey() + "</span> " + alt.getValue() + " ";
                            consHtml += "</div>";
                        }
                    }
                } else {
                    consHtml = "<span class='badge b-agree'>✓ All agree (" + r.consensus.agreementCount() + " fields)</span>";
                }
            }

            // Expected column
            var expHtml = new StringBuilder();
            for (var f : r.fields.entrySet()) {
                if (f.getValue().expected.isEmpty()) continue;
                expHtml.append("<div class='expected'><span class='field-label'>").append(fieldLabel(f.getKey()))
                        .append(":</span> ").append(f.getValue().expected).append("</div>");
            }

            // Actual output column
            var actHtml = new StringBuilder();
            for (var f : r.fields.entrySet()) {
                var d = f.getValue();
                var icon = d.match ? "<span class='match'>✓</span>" : "<span class='miss'>✗</span>";
                var srcBadge = "<span class='badge " + badgeCls(d.sourceStructurer) + "'>" + d.sourceStructurer + "</span>";
                actHtml.append("<div class='actual'>").append(icon).append(" <span class='field-label'>")
                        .append(fieldLabel(f.getKey())).append(":</span> <span class='field-val'>")
                        .append(d.actual.isEmpty() ? "<i>empty</i>" : d.actual)
                        .append("</span> ").append(srcBadge);
                if (!d.agreed) actHtml.append(" <span class='badge b-disagree'>⚠</span>");
                actHtml.append("</div>");
            }

            var accBadge = r.accuracy == 1.0 ? "b-ok" : "b-fail";
            html.append(String.format("<tr><td><b>%s</b></td><td>%s</td><td>%s</td><td>%s</td>" +
                            "<td class='raw-addr'>%s</td><td>%s</td><td>%s</td><td><span class='badge %s'>%.0f%%</span></td></tr>",
                    r.fixtureId, r.country, badges, consHtml, r.raw, expHtml, actHtml, accBadge, r.accuracy * 100));
        }
        html.append("</table></body></html>");

        Files.writeString(REPORT_PATH, html.toString());
        System.out.printf("%n=== ACCURACY REPORT ===%nMode: %s%nTotal: %d, %.0f%% accuracy, %d perfect, %dms avg%n",
                structurerMode, total, avgAcc * 100, perfect, avgMs);
        System.out.printf("Consensus: %d agreed, %d flagged%n", consensusOk, consensusFlagged);
        structurerCounts.forEach((s, c) -> System.out.printf("  %s: %d%n", s, c));
        System.out.println("Report: " + REPORT_PATH.toAbsolutePath());
    }

    private static boolean isReachable(String host, int port) {
        try (var s = new java.net.Socket()) { s.connect(new java.net.InetSocketAddress(host, port), 500); return true; }
        catch (Exception e) { return false; }
    }

    private static String card(Object v, String l) { return "<div class='card'><div class='v'>" + v + "</div><div class='l'>" + l + "</div></div>"; }
    private static String card(Object v, String l, String cls) { return "<div class='card'><div class='v'><span class='badge " + cls + "' style='font-size:20px;padding:4px 14px'>" + v + "</span></div><div class='l'>" + l + "</div></div>"; }

    private static String badgeCls(String s) {
        return switch (s) { case "libpostal" -> "b-lib"; case "stub" -> "b-stub"; default -> s.contains("gpt") || s.contains("azure") ? "b-gpt" : s.contains("glm") ? "b-glm" : "b-src"; };
    }

    private static String fieldLabel(String f) {
        return switch (f) { case "CTRY" -> "Country"; case "TWN_NM" -> "City"; case "CTRY_SUB_DVSN" -> "State"; case "STRT_NM" -> "Street"; case "BLDG_NB" -> "Bldg #"; case "BLDG_NM" -> "Bldg"; case "PST_CD" -> "Postal"; case "ADR_LINE" -> "Line"; default -> f; };
    }
}

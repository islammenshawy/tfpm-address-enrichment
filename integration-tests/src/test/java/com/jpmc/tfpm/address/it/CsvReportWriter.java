package com.jpmc.tfpm.address.it;

import com.jpmc.tfpm.address.it.AccuracyTestBase.ConsensusInfo;
import com.jpmc.tfpm.address.it.AccuracyTestBase.FieldDetail;
import com.jpmc.tfpm.address.it.AccuracyTestBase.FixtureResult;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streams enrichment results to a CSV file row-by-row.
 * No in-memory accumulation — safe for thousands of records.
 *
 * <p>Each fixture produces one summary row. Field details are
 * serialized as pipe-delimited pairs within a single CSV cell.
 *
 * <p>Thread-safe: multiple threads can call {@link #writeRow} concurrently.
 */
final class CsvReportWriter implements Closeable {

    private final BufferedWriter writer;
    private final AtomicBoolean headerWritten = new AtomicBoolean(false);

    CsvReportWriter(Path path) throws IOException {
        this.writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(path.toFile()), StandardCharsets.UTF_8));
        // Write BOM for Excel compatibility
        writer.write('\ufeff');
    }

    synchronized void writeRow(FixtureResult r) throws IOException {
        if (headerWritten.compareAndSet(false, true)) {
            writer.write(String.join(",",
                    "Fixture ID", "Country", "Outcome", "Recommendation",
                    "Overall Confidence",
                    "Sources", "Raw Address",
                    "Fields (name=value)", "Fields (expected)",
                    "Accuracy", "Expected Count", "Matched Count",
                    "Consensus Sources", "Consensus Agreements", "Consensus Disagreements",
                    "Consensus Overall", "Consensus Details",
                    "Review Reasons",
                    "Latency (ms)"));
            writer.newLine();
        }

        var row = new StringJoiner(",");
        row.add(csv(r.id()));
        row.add(csv(r.country()));
        row.add(csv(r.outcome()));
        row.add(csv(r.recommendation()));
        row.add(String.format("%.2f", r.overallConfidence()));
        row.add(csv(String.join(" | ", r.sources())));
        row.add(csv(r.raw()));

        // Actual fields: CTRY=US | TWN_NM=New York | ...
        var actualFields = new StringJoiner(" | ");
        var expectedFields = new StringJoiner(" | ");
        for (var f : r.fields().entrySet()) {
            var d = f.getValue();
            actualFields.add(f.getKey() + "=" + d.actual() + " (" + Math.round(d.confidence() * 100) + "%)");
            if (!d.expected().isEmpty()) {
                var mark = d.match() ? "✓" : "✗";
                expectedFields.add(f.getKey() + "=" + d.expected() + " " + mark);
            }
        }
        row.add(csv(actualFields.toString()));
        row.add(csv(expectedFields.toString()));

        // Accuracy
        row.add(r.accuracy() >= 0 ? String.format("%.0f%%", r.accuracy() * 100) : "n/a");
        row.add(String.valueOf(r.expectedCount()));
        row.add(String.valueOf(r.matchedCount()));

        // Consensus
        if (r.consensus() != null) {
            var c = r.consensus();
            row.add(String.valueOf(c.sourceCount()));
            row.add(String.valueOf(c.agreements()));
            row.add(String.valueOf(c.disagreements()));
            row.add(String.format("%.0f%%", c.overall() * 100));

            var details = new StringJoiner(" | ");
            for (var fc : c.fields().entrySet()) {
                var fci = fc.getValue();
                if (fci.agreed()) {
                    details.add(fc.getKey() + ": ✓ agreed (" + fci.consensusValue() + ")");
                } else {
                    var sources = new StringJoiner(", ");
                    fci.sourceValues().forEach((src, val) -> sources.add(src + "=" + val));
                    details.add(fc.getKey() + ": ⚠ DISAGREE [" + sources + "]");
                }
            }
            row.add(csv(details.toString()));
        } else {
            row.add(""); // sourceCount
            row.add(""); // agreements
            row.add(""); // disagreements
            row.add(""); // overall
            row.add("single source");
        }

        // Review reasons
        row.add(csv(String.join(" | ", r.reviewReasons())));

        row.add(String.valueOf(r.latencyMs()));

        writer.write(row.toString());
        writer.newLine();
        writer.flush();
    }

    /**
     * Write summary stats at the end of the CSV. Call after all rows are written.
     * Uses running counters — no need to hold all results in memory.
     */
    synchronized void writeSummary(java.util.List<AccuracyTestBase.FixtureResult> results) throws IOException {
        if (results == null || results.isEmpty()) return;

        writer.newLine();
        writer.write("--- SUMMARY ---");
        writer.newLine();

        int total = results.size();
        var scored = results.stream().filter(r -> r.accuracy() >= 0).toList();
        double avgAcc = scored.isEmpty() ? -1 : scored.stream().mapToDouble(r -> r.accuracy()).average().orElse(0);
        long perfect = results.stream().filter(r -> r.accuracy() == 1.0).count();
        long avgMs = (long) results.stream().mapToLong(r -> r.latencyMs()).average().orElse(0);
        long success = results.stream().filter(r -> "SUCCESS".equals(r.outcome())).count();
        long review = results.stream().filter(r -> "REQUIRES_REVIEW".equals(r.outcome())).count();
        long unstructurable = results.stream().filter(r -> "UNSTRUCTURABLE".equals(r.outcome())).count();
        long consensusRuns = results.stream().filter(r -> r.consensus() != null).count();
        long disagreements = results.stream().filter(r -> r.consensus() != null && r.consensus().disagreements() > 0).count();

        writer.write("Total Fixtures," + total); writer.newLine();
        if (avgAcc >= 0) {
            writer.write("Accuracy," + String.format("%.0f%%", avgAcc * 100)); writer.newLine();
            writer.write("Perfect Matches," + perfect); writer.newLine();
        }
        writer.write("Avg Latency (ms)," + avgMs); writer.newLine();
        writer.write("SUCCESS," + success); writer.newLine();
        writer.write("REQUIRES_REVIEW," + review); writer.newLine();
        writer.write("UNSTRUCTURABLE," + unstructurable); writer.newLine();
        writer.write("Consensus Runs," + consensusRuns); writer.newLine();
        writer.write("Disagreements," + disagreements); writer.newLine();

        // Per-country breakdown
        writer.newLine();
        writer.write("--- PER COUNTRY ---"); writer.newLine();
        writer.write("Country,Fixtures,Accuracy,Perfect,Avg Latency (ms),SUCCESS,REVIEW,Disagreements"); writer.newLine();

        var byCountry = new java.util.TreeMap<String, java.util.List<AccuracyTestBase.FixtureResult>>();
        results.forEach(r -> byCountry.computeIfAbsent(r.country(), k -> new java.util.ArrayList<>()).add(r));

        for (var entry : byCountry.entrySet()) {
            var cr = entry.getValue();
            var cScored = cr.stream().filter(r -> r.accuracy() >= 0).toList();
            var ca = cScored.isEmpty() ? -1 : cScored.stream().mapToDouble(r -> r.accuracy()).average().orElse(0);
            var cp = cr.stream().filter(r -> r.accuracy() == 1.0).count();
            var cl = (long) cr.stream().mapToLong(r -> r.latencyMs()).average().orElse(0);
            var ok = cr.stream().filter(r -> "SUCCESS".equals(r.outcome())).count();
            var rev = cr.stream().filter(r -> "REQUIRES_REVIEW".equals(r.outcome())).count();
            var cd = cr.stream().filter(r -> r.consensus() != null && r.consensus().disagreements() > 0).count();
            writer.write(String.format("%s,%d,%s,%d,%d,%d,%d,%d",
                    entry.getKey(), cr.size(),
                    ca >= 0 ? String.format("%.0f%%", ca * 100) : "n/a",
                    cp, cl, ok, rev, cd));
            writer.newLine();
        }

        writer.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }

    private static String csv(String value) {
        if (value == null) return "";
        // Escape for CSV: wrap in quotes if contains comma, quote, or newline
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}

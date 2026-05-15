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
                    "Fixture ID", "Country", "Outcome", "Overall Confidence",
                    "Sources", "Raw Address",
                    "Fields (name=value)", "Fields (expected)",
                    "Accuracy", "Expected Count", "Matched Count",
                    "Consensus Sources", "Consensus Agreements", "Consensus Disagreements",
                    "Consensus Overall", "Consensus Details",
                    "Latency (ms)"));
            writer.newLine();
        }

        var row = new StringJoiner(",");
        row.add(csv(r.id()));
        row.add(csv(r.country()));
        row.add(csv(r.outcome()));
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

        row.add(String.valueOf(r.latencyMs()));

        writer.write(row.toString());
        writer.newLine();
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

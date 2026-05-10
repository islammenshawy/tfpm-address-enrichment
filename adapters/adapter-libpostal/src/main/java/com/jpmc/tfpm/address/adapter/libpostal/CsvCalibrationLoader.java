package com.jpmc.tfpm.address.adapter.libpostal;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-country confidence calibrator loaded from a CSV file.
 * Uses piecewise-linear interpolation between calibration points.
 *
 * <p>CSV format: {@code country,field,raw_low,raw_high,calibrated_low,calibrated_high}
 *
 * <p>Falls back to identity calibration (clamp to [0,1]) for missing
 * (country, field) combinations.
 */
@ThreadSafe
public final class CsvCalibrationLoader implements ConfidenceCalibrator {

    private static final Logger LOG = LoggerFactory.getLogger(CsvCalibrationLoader.class);

    private final String structurer;
    private final Map<String, CalibrationSegment> calibrationTable;

    public CsvCalibrationLoader(String structurerName, String csvResource) {
        this.structurer = structurerName;
        this.calibrationTable = loadCsv(csvResource);
        LOG.info("Loaded {} calibration segments for structurer '{}'",
                calibrationTable.size(), structurerName);
    }

    @Override
    public String structurerName() {
        return structurer;
    }

    @Override
    public double calibrate(double raw, AddressField field, String countryCode) {
        if (Double.isNaN(raw) || Double.isInfinite(raw)) return 0.0;

        var key = countryCode.toUpperCase() + "|" + field.name();
        var segment = calibrationTable.get(key);

        if (segment == null) {
            // Fallback to identity calibration
            return Math.max(0.0, Math.min(1.0, raw));
        }

        return segment.interpolate(raw);
    }

    private static Map<String, CalibrationSegment> loadCsv(String resource) {
        if (resource == null || resource.isBlank()) return Map.of();
        try {
            var res = new DefaultResourceLoader().getResource(resource);
            if (!res.exists()) {
                LOG.warn("Calibration CSV not found: {}", resource);
                return Map.of();
            }

            var table = new HashMap<String, CalibrationSegment>();
            try (var reader = new BufferedReader(
                    new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean header = true;
                while ((line = reader.readLine()) != null) {
                    if (header) { header = false; continue; }
                    var trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                    var parts = trimmed.split(",");
                    if (parts.length < 6) continue;

                    var country = parts[0].trim().toUpperCase();
                    var field = parts[1].trim();
                    var rawLow = Double.parseDouble(parts[2].trim());
                    var rawHigh = Double.parseDouble(parts[3].trim());
                    var calLow = Double.parseDouble(parts[4].trim());
                    var calHigh = Double.parseDouble(parts[5].trim());

                    table.put(country + "|" + field,
                            new CalibrationSegment(rawLow, rawHigh, calLow, calHigh));
                }
            }
            return Collections.unmodifiableMap(table);
        } catch (IOException e) {
            LOG.error("Failed to load calibration CSV: {}", resource, e);
            return Map.of();
        }
    }

    private record CalibrationSegment(double rawLow, double rawHigh,
                                       double calLow, double calHigh) {
        double interpolate(double raw) {
            if (raw <= rawLow) return calLow;
            if (raw >= rawHigh) return calHigh;
            double t = (raw - rawLow) / (rawHigh - rawLow);
            return calLow + t * (calHigh - calLow);
        }
    }
}

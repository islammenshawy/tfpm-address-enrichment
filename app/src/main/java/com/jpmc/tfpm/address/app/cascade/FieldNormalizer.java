package com.jpmc.tfpm.address.app.cascade;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Normalizes structurer output to canonical forms so all sources
 * (libpostal, LLMs) use the same conventions for consensus comparison.
 *
 * <p>Two layers:
 * <ol>
 *   <li><b>Universal</b> — Unicode NFKD, whitespace, trim. Safe everywhere.</li>
 *   <li><b>Dictionary-based</b> — libpostal's per-language street type
 *       dictionaries. Maps LLM abbreviations to the same canonical forms
 *       libpostal uses internally: "St"→"street", "Str."→"strasse",
 *       "大道"→canonical Chinese form. 14 languages, 1094 entries.</li>
 * </ol>
 *
 * <p>libpostal already normalizes its own output using these dictionaries.
 * We apply the SAME dictionaries to LLM output so both sources produce
 * matching canonical forms for consensus comparison.
 *
 * <p>Source: openvenues/libpostal dictionaries (MIT license).
 */
@ThreadSafe
public final class FieldNormalizer {

    private static final Logger LOG = LoggerFactory.getLogger(FieldNormalizer.class);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[,;.]+$");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    private final Map<String, String> streetTypeMap;

    public FieldNormalizer() {
        this.streetTypeMap = loadDictionaries();
        if (!streetTypeMap.isEmpty()) {
            LOG.info("Loaded {} street type canonical mappings from libpostal dictionaries", streetTypeMap.size());
        }
    }

    /** Normalize all fields in a structuring result. */
    public StructuringResult normalize(StructuringResult result) {
        var normalized = new EnumMap<AddressField, FieldValue>(AddressField.class);
        for (var entry : result.fields().entrySet()) {
            var val = normalizeValue(entry.getValue().value(), entry.getKey());
            if (!val.isEmpty()) {
                normalized.put(entry.getKey(), new FieldValue(val, entry.getValue().confidence()));
            }
        }
        return new StructuringResult(result.structurerName(), normalized,
                result.latency(), result.diagnostics());
    }

    /** Normalize a single field value. */
    public String normalizeValue(String value, AddressField field) {
        if (value == null || value.isBlank()) return "";
        var s = value.trim();
        s = WHITESPACE.matcher(s).replaceAll(" ");
        s = TRAILING_PUNCT.matcher(s).replaceAll("");
        s = stripDiacritics(s);
        if (field == AddressField.CTRY && s.length() == 2) s = s.toUpperCase();
        if (field == AddressField.STRT_NM) s = canonicalizeStreetType(s);
        return s.trim();
    }

    /**
     * Replace street type abbreviations with libpostal canonical forms.
     * Checks last word (English: "Madison Ave"→"Madison avenue")
     * and first word (French: "Rue de..."→canonical, German: "Str. 14"→canonical).
     */
    String canonicalizeStreetType(String street) {
        if (street == null || street.isBlank() || streetTypeMap.isEmpty()) return street;
        var words = street.split("\\s+");
        if (words.length == 0) return street;

        // Last word (most common for English, Italian, Portuguese, Spanish)
        var last = words[words.length - 1].toLowerCase().replaceAll("[.,]$", "");
        var canonical = streetTypeMap.get(last);
        if (canonical != null) {
            words[words.length - 1] = canonical;
            return String.join(" ", words);
        }

        // First word (French, German, Arabic)
        var first = words[0].toLowerCase().replaceAll("[.,]$", "");
        canonical = streetTypeMap.get(first);
        if (canonical != null) {
            words[0] = canonical;
            return String.join(" ", words);
        }

        return street;
    }

    static String stripDiacritics(String input) {
        return DIACRITICS.matcher(Normalizer.normalize(input, Normalizer.Form.NFKD)).replaceAll("");
    }

    /**
     * Load all language dictionaries. Format: canonical|synonym1|synonym2|...
     * Every synonym maps to the canonical (first) form.
     */
    private static Map<String, String> loadDictionaries() {
        var map = new HashMap<String, String>(2000);
        try {
            var resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:normalization/street-types/*.txt");
            for (var res : resources) {
                try (var reader = new BufferedReader(
                        new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        var parts = line.split("\\|");
                        if (parts.length == 0) continue;
                        var canon = parts[0].trim();
                        map.putIfAbsent(canon.toLowerCase(), canon);
                        for (int i = 1; i < parts.length; i++) {
                            var syn = parts[i].trim();
                            if (!syn.isEmpty()) map.putIfAbsent(syn.toLowerCase(), canon);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("No normalization dictionaries found (running without): {}", e.getMessage());
        }
        return Collections.unmodifiableMap(map);
    }
}

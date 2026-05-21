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
 *   <li><b>Dictionary-based</b> — per-field dictionaries loaded from classpath.
 *       Maps abbreviations to canonical forms: "St"→"street", "Str."→"strasse",
 *       "大道"→canonical Chinese form, country names→ISO codes.</li>
 * </ol>
 *
 * <p>The mapping from {@link AddressField} to dictionary classpath pattern is
 * defined in {@link #FIELD_DICTIONARY_PATTERNS}. Adding normalization for a new
 * field requires only a new entry in that map and dropping dictionary files on
 * the classpath — no code changes.
 *
 * <p>Source: openvenues/libpostal dictionaries (MIT license).
 */
@ThreadSafe
public final class FieldNormalizer {

    private static final Logger LOG = LoggerFactory.getLogger(FieldNormalizer.class);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[,;.]+$");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    /**
     * Canonicalization strategy for a dictionary-backed field.
     * <ul>
     *   <li>{@link #FULL_VALUE} — the entire trimmed value is looked up (e.g. country names).</li>
     *   <li>{@link #FIRST_LAST_WORD} — only the first or last word is looked up
     *       (e.g. street type abbreviations that appear at the start or end of a street name).</li>
     * </ul>
     */
    enum NormalizationStrategy { FULL_VALUE, FIRST_LAST_WORD }

    /**
     * The ONLY place that knows which fields get which dictionaries.
     * Adding a new normalization (e.g. building types, directionals) is just
     * adding a new entry here and dropping dictionary files on the classpath.
     */
    private static final Map<AddressField, String> FIELD_DICTIONARY_PATTERNS = Map.of(
            AddressField.CTRY, "normalization/countries/*.txt",
            AddressField.STRT_NM, "normalization/street-types/*.txt",
            AddressField.CTRY_SUB_DVSN, "normalization/subdivisions/*.txt"
    );

    /**
     * Per-field canonicalization strategy. Fields not listed here default to
     * {@link NormalizationStrategy#FULL_VALUE}.
     */
    private static final Map<AddressField, NormalizationStrategy> FIELD_STRATEGIES = Map.of(
            AddressField.STRT_NM, NormalizationStrategy.FIRST_LAST_WORD
    );

    /** Loaded dictionaries keyed by AddressField. Each value is an unmodifiable synonym→canonical map. */
    private final Map<AddressField, Map<String, String>> fieldDictionaries;

    public FieldNormalizer() {
        var dictionaries = new EnumMap<AddressField, Map<String, String>>(AddressField.class);
        for (var entry : FIELD_DICTIONARY_PATTERNS.entrySet()) {
            var dict = loadDictionaries(entry.getValue());
            if (!dict.isEmpty()) {
                dictionaries.put(entry.getKey(), dict);
            }
        }
        this.fieldDictionaries = Collections.unmodifiableMap(dictionaries);
        if (!fieldDictionaries.isEmpty()) {
            var sb = new StringBuilder("Loaded normalization dictionaries:");
            fieldDictionaries.forEach((field, dict) ->
                    sb.append(" ").append(field).append("=").append(dict.size()));
            LOG.info("{}", sb);
        }
    }

    /** Normalize CTRY field only — for libpostal whose street types are already canonical. */
    public StructuringResult normalizeCountryOnly(StructuringResult result) {
        var ctryDict = fieldDictionaries.get(AddressField.CTRY);
        if (ctryDict == null || ctryDict.isEmpty()) {
            return result;
        }
        var normalized = new EnumMap<AddressField, FieldValue>(AddressField.class);
        for (var entry : result.fields().entrySet()) {
            if (entry.getKey() == AddressField.CTRY) {
                var val = canonicalize(entry.getValue().value().trim(), ctryDict, AddressField.CTRY);
                if (!val.isEmpty()) {
                    normalized.put(entry.getKey(), new FieldValue(val, entry.getValue().confidence()));
                }
            } else {
                normalized.put(entry.getKey(), entry.getValue());
            }
        }
        return new StructuringResult(result.structurerName(), normalized,
                result.latency(), result.diagnostics());
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

    /** Normalize a single field value. Preserves diacritics (Zürich stays Zürich). */
    public String normalizeValue(String value, AddressField field) {
        if (value == null || value.isBlank()) return "";
        var s = value.trim();
        s = WHITESPACE.matcher(s).replaceAll(" ");
        s = TRAILING_PUNCT.matcher(s).replaceAll("");
        var dict = fieldDictionaries.get(field);
        if (dict != null && !dict.isEmpty()) {
            s = canonicalize(s, dict, field);
        }
        return s.trim();
    }

    /**
     * Apply dictionary-based canonicalization to a value.
     *
     * <p>The strategy is determined by {@link #FIELD_STRATEGIES}:
     * <ul>
     *   <li>{@link NormalizationStrategy#FULL_VALUE} — look up the entire value
     *       (with short-value uppercase pass-through for 2/3-char country codes).</li>
     *   <li>{@link NormalizationStrategy#FIRST_LAST_WORD} — try last word then first word
     *       (street type at start or end of a street name).</li>
     * </ul>
     */
    String canonicalize(String value, Map<String, String> dictionary, AddressField field) {
        if (value == null || value.isBlank() || dictionary.isEmpty()) return value;
        var strategy = FIELD_STRATEGIES.getOrDefault(field, NormalizationStrategy.FULL_VALUE);
        return switch (strategy) {
            case FULL_VALUE -> canonicalizeFullValue(value, dictionary);
            case FIRST_LAST_WORD -> canonicalizeFirstLastWord(value, dictionary);
        };
    }

    /**
     * Full-value lookup: the entire string is the key.
     * Short values (2-3 chars) are uppercased as-is (ISO country code pass-through).
     */
    private static String canonicalizeFullValue(String value, Map<String, String> dictionary) {
        if (value.length() <= 3) return value.toUpperCase();
        var canonical = dictionary.get(value.toLowerCase().trim());
        return canonical != null ? canonical : value;
    }

    /**
     * First/last word lookup for street-type-style fields.
     * Checks last word first (English: "Madison Ave"→"Madison avenue"),
     * then first word (French: "Rue de..."→canonical, German: "Str. 14"→canonical).
     */
    private static String canonicalizeFirstLastWord(String value, Map<String, String> dictionary) {
        var words = value.split("\\s+");
        if (words.length == 0) return value;

        // Last word (most common for English, Italian, Portuguese, Spanish)
        var last = words[words.length - 1].toLowerCase().replaceAll("[.,]$", "");
        var canonical = dictionary.get(last);
        if (canonical != null) {
            words[words.length - 1] = canonical;
            return String.join(" ", words);
        }

        // First word (French, German, Arabic)
        var first = words[0].toLowerCase().replaceAll("[.,]$", "");
        canonical = dictionary.get(first);
        if (canonical != null) {
            words[0] = canonical;
            return String.join(" ", words);
        }

        return value;
    }

    static String stripDiacritics(String input) {
        return DIACRITICS.matcher(Normalizer.normalize(input, Normalizer.Form.NFKD)).replaceAll("");
    }

    /**
     * Load dictionaries from classpath. Format: canonical|synonym1|synonym2|...
     * Every synonym maps to the canonical (first) form.
     */
    private static Map<String, String> loadDictionaries(String pattern) {
        var map = new HashMap<String, String>(2000);
        try {
            var resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:" + pattern);
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

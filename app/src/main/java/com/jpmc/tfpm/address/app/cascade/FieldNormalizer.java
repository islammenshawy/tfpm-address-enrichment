package com.jpmc.tfpm.address.app.cascade;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import java.text.Normalizer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Universal field normalization applied to structurer output BEFORE
 * the FieldMerger and ConsensusAnalyzer compare values.
 *
 * <p>Only applies normalizations that are SAFE globally (all countries,
 * all languages). Country-specific normalization belongs in the LLM
 * prompt or per-country pre-processors, not here.
 *
 * <p>Safe normalizations:
 * <ul>
 *   <li>Unicode NFKD decomposition — Zürich→Zurich, Genève→Geneve</li>
 *   <li>Whitespace collapse — "New  York " → "New York"</li>
 *   <li>Trailing punctuation strip — "London," → "London"</li>
 *   <li>Leading/trailing whitespace trim</li>
 * </ul>
 *
 * <p>NOT applied (country-specific, risk of false matches):
 * <ul>
 *   <li>Street type expansion (St→Street) — English-only</li>
 *   <li>Building number extraction (Suite 4500→4500) — Western-only</li>
 *   <li>Article stripping (Al Wasl→Wasl) — loses meaning in Arabic</li>
 *   <li>Word order normalization — too risky globally</li>
 * </ul>
 */
@ThreadSafe
public final class FieldNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[,;.]+$");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    /**
     * Normalize all fields in a structuring result.
     * Returns a new StructuringResult with normalized values.
     */
    public StructuringResult normalize(StructuringResult result) {
        var normalized = new EnumMap<AddressField, FieldValue>(AddressField.class);
        for (var entry : result.fields().entrySet()) {
            var field = entry.getKey();
            var fv = entry.getValue();
            var normalizedValue = normalizeValue(fv.value(), field);
            if (!normalizedValue.isEmpty()) {
                normalized.put(field, new FieldValue(normalizedValue, fv.confidence()));
            }
        }
        return new StructuringResult(result.structurerName(), normalized,
                result.latency(), result.diagnostics());
    }

    /**
     * Normalize a single field value. Safe for all countries/languages.
     */
    public String normalizeValue(String value, AddressField field) {
        if (value == null || value.isBlank()) return "";

        var s = value;

        // 1. Trim
        s = s.trim();

        // 2. Collapse whitespace
        s = WHITESPACE.matcher(s).replaceAll(" ");

        // 3. Strip trailing punctuation (commas, semicolons, periods)
        s = TRAILING_PUNCT.matcher(s).replaceAll("");

        // 4. Unicode NFKD decomposition — removes diacritics for comparison
        //    but preserves the base characters
        s = stripDiacritics(s);

        // 5. CTRY field: force uppercase 2-letter code
        if (field == AddressField.CTRY && s.length() == 2) {
            s = s.toUpperCase();
        }

        return s.trim();
    }

    /**
     * Normalize for comparison only (more aggressive — used by consensus).
     * Strips diacritics AND lowercases. The stored value keeps original form.
     */
    public String normalizeForComparison(String value) {
        if (value == null) return "";
        var s = normalizeValue(value, null);
        return s.toLowerCase();
    }

    /**
     * Unicode NFKD: decomposes characters and removes combining marks.
     * Zürich → Zurich, Genève → Geneve, São Paulo → Sao Paulo.
     */
    static String stripDiacritics(String input) {
        var decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD);
        return DIACRITICS.matcher(decomposed).replaceAll("");
    }
}

package com.jpmc.tfpm.address.domain;

import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The raw, unstructured address as it appears in a source system.
 *
 * <p>Always immutable. Constructed by an inbound channel adapter from the
 * channel-specific message format and passed through the cascade unchanged.
 *
 * @param raw          the free-text address content. UTF-8. Up to 2000 chars
 *                     (longer is rejected by the channel adapter, not here).
 *                     Never null, may be empty (handle gracefully downstream).
 * @param countryHint  ISO 3166-1 alpha-2 country code if known from context
 *                     (e.g. derived from a BIC or routing number). Empty
 *                     string means "unknown". Structurers MAY use this as a
 *                     hint but MUST NOT blindly trust it.
 * @param locale       BCP 47 locale tag (e.g. "en-AE", "ar-SA"). Empty
 *                     string means "unknown". Helps with transliteration.
 */
public record RawAddress(String raw, String countryHint, String locale) {

    private static final Logger LOG = LoggerFactory.getLogger(RawAddress.class);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ISO_ALPHA2 = Pattern.compile("[A-Z]{2}");
    public RawAddress {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(countryHint, "countryHint");
        Objects.requireNonNull(locale, "locale");
        if (raw.length() > 2000) {
            throw new IllegalArgumentException(
                    "raw address exceeds 2000 chars; channel adapter must reject earlier");
        }
        if (!countryHint.isEmpty()) {
            countryHint = countryHint.toUpperCase(java.util.Locale.ROOT);
            if (!ISO_ALPHA2.matcher(countryHint).matches()) {
                LOG.warn("Invalid countryHint '{}' — not ISO 3166-1 alpha-2; treating as empty", countryHint);
                countryHint = "";
            }
        }
    }

    /**
     * Convenience factory for the common case where only the raw text is known.
     */
    public static RawAddress of(String raw) {
        return new RawAddress(raw, "", "");
    }

    /**
     * Canonical form used as input to the idempotency hash. Trims, collapses
     * runs of whitespace to a single space, lowercases. Does NOT normalise
     * unicode (that's a structurer concern).
     */
    public String canonical() {
        return WHITESPACE.matcher(raw.trim()).replaceAll(" ").toLowerCase();
    }
}

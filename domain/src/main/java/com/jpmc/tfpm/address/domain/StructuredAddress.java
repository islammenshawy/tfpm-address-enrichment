package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The post-cascade output of the enrichment service.
 *
 * <p>Each field carries the calibrated confidence chosen by the
 * {@code FieldMerger} from whichever structurer reported the highest
 * confidence on that specific field. Fields the cascade could not infer
 * are absent from the map (not present with empty value).
 *
 * <p>Maps 1:1 onto the ISO 20022 {@code PstlAdr} element. The
 * {@code adapter-prowide} module converts this into Prowide's
 * {@code PostalAddress27} (or current SRU equivalent) for emission into
 * MX messages.
 *
 * @param fields per-field calibrated values; absent keys indicate the
 *               field could not be inferred. Immutable.
 */
public record StructuredAddress(Map<AddressField, FieldValue> fields) {

    public StructuredAddress {
        Objects.requireNonNull(fields, "fields");
        fields = Map.copyOf(fields);
    }

    public static StructuredAddress empty() {
        return new StructuredAddress(Map.of());
    }

    public Optional<FieldValue> get(AddressField field) {
        return Optional.ofNullable(fields.get(field));
    }

    /**
     * SR2026 minimum mandatory fields are TWN_NM and CTRY. A structured
     * address that is missing either cannot be sent on the SWIFT network
     * after 14 November 2026 and should go to the exception queue.
     */
    public boolean meetsSr2026Minimum() {
        return fields.containsKey(AddressField.TWN_NM)
                && fields.containsKey(AddressField.CTRY);
    }

    /**
     * Overall confidence is the minimum across required fields. If a
     * required field is missing, returns 0.
     */
    public double overallConfidence() {
        if (!meetsSr2026Minimum()) return 0.0;
        return Math.min(
                fields.get(AddressField.TWN_NM).confidence(),
                fields.get(AddressField.CTRY).confidence());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Stateful builder; do NOT use across threads. */
    public static final class Builder {
        private final EnumMap<AddressField, FieldValue> fields =
                new EnumMap<>(AddressField.class);

        public Builder put(AddressField field, FieldValue value) {
            fields.put(field, value);
            return this;
        }

        public Builder put(AddressField field, String value, double confidence) {
            fields.put(field, new FieldValue(value, confidence));
            return this;
        }

        public StructuredAddress build() {
            return new StructuredAddress(fields);
        }
    }
}

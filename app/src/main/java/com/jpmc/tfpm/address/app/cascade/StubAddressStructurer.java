package com.jpmc.tfpm.address.app.cascade;

import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.Calibrated;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic stub structurer for testing. Extracts country from
 * countryHint and uses raw text as town name.
 */
@Component
@ThreadSafe
@Calibrated
@ConditionalOnProperty(name = "enrichment.test.stub.enabled", havingValue = "true")
public final class StubAddressStructurer implements AddressStructurer {

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public Set<AddressField> supportedFields() {
        return EnumSet.of(
                AddressField.CTRY,
                AddressField.TWN_NM,
                AddressField.STRT_NM,
                AddressField.PST_CD,
                AddressField.CTRY_SUB_DVSN,
                AddressField.BLDG_NB,
                AddressField.BLDG_NM);
    }

    @Override
    public StructuringResult structure(RawAddress raw) {
        var fields = new java.util.EnumMap<AddressField, FieldValue>(AddressField.class);

        if (!raw.countryHint().isEmpty()) {
            fields.put(AddressField.CTRY, new FieldValue(raw.countryHint(), 0.95));
        }

        // Use first comma-separated token as town name
        var parts = raw.raw().split(",");
        if (parts.length > 0) {
            fields.put(AddressField.TWN_NM, new FieldValue(parts[0].trim(), 0.85));
        }
        if (parts.length > 1) {
            fields.put(AddressField.STRT_NM, new FieldValue(parts[1].trim(), 0.80));
        }

        return new StructuringResult(name(), fields, Duration.ofMillis(1), Map.of());
    }
}

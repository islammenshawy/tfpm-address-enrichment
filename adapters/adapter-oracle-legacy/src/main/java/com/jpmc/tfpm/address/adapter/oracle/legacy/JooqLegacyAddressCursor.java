package com.jpmc.tfpm.address.adapter.oracle.legacy;

import com.jpmc.tfpm.address.domain.LegacyAddressCursor;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.jooq.impl.DSL.*;

@ThreadSafe
public final class JooqLegacyAddressCursor implements LegacyAddressCursor {

    private static final Logger LOG = LoggerFactory.getLogger(JooqLegacyAddressCursor.class);

    private final DSLContext dsl;

    public JooqLegacyAddressCursor(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public long streamAll(Consumer<LegacyAddress> consumer) {
        var count = new AtomicLong(0);

        try (var cursor = dsl.select(
                        field("PARTY_ID"),
                        field("ADDRESS_LINE_1"),
                        field("ADDRESS_LINE_2"),
                        field("ADDRESS_LINE_3"),
                        field("ADDRESS_LINE_4"),
                        field("COUNTRY_CODE"))
                .from(table("ADDRESS"))
                .fetchLazy()) {

            while (cursor.hasNext()) {
                var record = cursor.fetchNext();
                var raw = buildRaw(record);
                if (raw.isBlank()) continue;

                var countryCode = record.get(field("COUNTRY_CODE"), String.class);
                var countryHint = countryCode != null && countryCode.length() == 2 ? countryCode : "";
                var partyId = record.get(field("PARTY_ID"), String.class);

                consumer.accept(new LegacyAddress(raw, countryHint, partyId != null ? partyId : "unknown"));
                count.incrementAndGet();
            }
        }

        LOG.info("Streamed {} legacy addresses", count.get());
        return count.get();
    }

    private String buildRaw(org.jooq.Record record) {
        var sb = new StringBuilder();
        for (var col : new String[]{"ADDRESS_LINE_1", "ADDRESS_LINE_2", "ADDRESS_LINE_3", "ADDRESS_LINE_4"}) {
            var line = record.get(field(col), String.class);
            if (line != null && !line.isBlank()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(line.trim());
            }
        }
        return sb.toString();
    }
}

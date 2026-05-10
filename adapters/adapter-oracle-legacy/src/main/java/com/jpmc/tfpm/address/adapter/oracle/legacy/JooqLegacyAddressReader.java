package com.jpmc.tfpm.address.adapter.oracle.legacy;

import com.jpmc.tfpm.address.domain.LegacyAddressReader;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.stream.Stream;

import static org.jooq.impl.DSL.*;

/**
 * Read-only jOOQ access to the legacy Oracle address schema.
 * Uses the TFPM_LEGACY_RO user (SELECT-only privileges).
 * No INSERT, UPDATE, DELETE, or MERGE operations anywhere in this class.
 */
@ThreadSafe
public final class JooqLegacyAddressReader implements LegacyAddressReader {

    private static final Logger LOG = LoggerFactory.getLogger(JooqLegacyAddressReader.class);

    private final DSLContext dsl;

    public JooqLegacyAddressReader(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Stream<LegacyAddressRow> readAll() {
        return dsl.select(
                        field("PARTY_ID"),
                        field("SOURCE_TABLE"),
                        field("ADDRESS_TEXT"),
                        field("COUNTRY_CODE"))
                .from(table("LEGACY_ADDRESSES"))
                .fetchStream()
                .map(r -> new LegacyAddressRow(
                        r.get(field("PARTY_ID"), String.class),
                        r.get(field("SOURCE_TABLE"), String.class),
                        r.get(field("ADDRESS_TEXT"), String.class),
                        Optional.ofNullable(r.get(field("COUNTRY_CODE"), String.class))
                                .orElse("")));
    }

    @Override
    public Optional<LegacyAddressRow> findByPartyId(String partyId) {
        var row = dsl.select(
                        field("PARTY_ID"),
                        field("SOURCE_TABLE"),
                        field("ADDRESS_TEXT"),
                        field("COUNTRY_CODE"))
                .from(table("LEGACY_ADDRESSES"))
                .where(field("PARTY_ID").eq(partyId))
                .fetchOne();

        if (row == null) return Optional.empty();

        return Optional.of(new LegacyAddressRow(
                row.get(field("PARTY_ID"), String.class),
                row.get(field("SOURCE_TABLE"), String.class),
                row.get(field("ADDRESS_TEXT"), String.class),
                Optional.ofNullable(row.get(field("COUNTRY_CODE"), String.class))
                        .orElse("")));
    }

    @Override
    public long approximateCount() {
        var count = dsl.selectCount()
                .from(table("LEGACY_ADDRESSES"))
                .fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }
}

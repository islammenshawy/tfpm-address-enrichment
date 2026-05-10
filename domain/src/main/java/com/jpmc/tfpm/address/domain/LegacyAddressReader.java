package com.jpmc.tfpm.address.domain;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Read-only access to the legacy Oracle store of unstructured party
 * addresses. Implemented by {@code adapter-oracle-legacy} using jOOQ.
 *
 * <p>The implementation MUST use a HikariCP pool bound to the
 * {@code TFPM_LEGACY_RO} Oracle user, which has SELECT-only privileges.
 * Even if a future bug in this module attempts to write, it fails at the
 * database level with insufficient privileges.
 *
 * <p>The {@code archunit-tests} module additionally enforces that no class
 * in the legacy adapter module emits any INSERT, UPDATE, DELETE, or MERGE
 * jOOQ DSL operation. This is belt-and-braces; both the DB grant and the
 * static check must hold.
 *
 * <p>Implementations MUST be {@code @ThreadSafe}.
 */
public interface LegacyAddressReader {

    /**
     * Stream all legacy address rows. Cursor-based, no in-memory load.
     * The caller MUST close the stream (use try-with-resources).
     *
     * <p>The Spring Batch backfill job uses this to drive partitioned
     * processing across {@code N} workers.
     */
    Stream<LegacyAddressRow> readAll();

    /**
     * Look up a single legacy row by party id. Used by ad-hoc lookups
     * and by replay-from-DLT recovery flows.
     */
    Optional<LegacyAddressRow> findByPartyId(String partyId);

    /**
     * Approximate row count, used for backfill progress estimation only.
     * Implementations may return a cached value updated periodically;
     * exact accuracy is not required.
     */
    long approximateCount();

    /**
     * A single legacy address row, with the raw party id preserved so the
     * eventual structured result can be correlated back to the source.
     *
     * @param partyId       legacy primary key (e.g. CUSTOMER_ID, COUNTERPARTY_ID)
     * @param sourceTable   which legacy table this came from (for audit)
     * @param raw           the raw address fields concatenated by the adapter,
     *                      pre-canonicalisation
     * @param countryHint   country code from the source row if a discrete
     *                      column existed; "" otherwise
     */
    record LegacyAddressRow(
            String partyId,
            String sourceTable,
            String raw,
            String countryHint) {
        public RawAddress toRawAddress() {
            return new RawAddress(raw, countryHint, "");
        }
    }
}

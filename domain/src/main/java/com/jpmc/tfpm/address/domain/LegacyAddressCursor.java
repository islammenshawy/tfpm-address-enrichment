package com.jpmc.tfpm.address.domain;

import java.util.function.Consumer;

/**
 * Port for streaming legacy addresses for batch backfill.
 * Implementation lives in adapter-oracle-legacy.
 */
public interface LegacyAddressCursor {

    /**
     * Stream all legacy addresses through the consumer.
     * The consumer receives (rawAddress, countryHint, partyId) tuples.
     *
     * @param consumer processes each address
     * @return total number of addresses streamed
     */
    long streamAll(Consumer<LegacyAddress> consumer);

    record LegacyAddress(String raw, String countryHint, String partyId) {}
}

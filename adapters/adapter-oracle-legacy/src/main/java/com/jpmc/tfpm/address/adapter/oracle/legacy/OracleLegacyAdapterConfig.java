package com.jpmc.tfpm.address.adapter.oracle.legacy;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean registration for the legacy Oracle read-only adapter.
 * Uses the {@code legacyReadDsl} qualified DSLContext.
 */
@Configuration
public class OracleLegacyAdapterConfig {

    @Bean
    public JooqLegacyAddressReader jooqLegacyAddressReader(
            @Qualifier("legacyReadDsl") DSLContext legacyReadDsl) {
        return new JooqLegacyAddressReader(legacyReadDsl);
    }
}

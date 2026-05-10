package com.jpmc.tfpm.address.adapter.prowide;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean registration for the Prowide ISO 20022 adapter.
 */
@Configuration
public class ProwideAdapterConfig {

    @Bean
    public ProwideAddressMapper prowideAddressMapper() {
        return new ProwideAddressMapper();
    }

    @Bean
    public MxMessageEnricher mxMessageEnricher(ProwideAddressMapper mapper) {
        return new MxMessageEnricher(mapper);
    }
}

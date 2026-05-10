package com.jpmc.tfpm.address.inbound.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body for address enrichment.
 */
public record EnrichmentHttpRequest(
        @NotBlank(message = "rawAddress must not be blank")
        @Size(max = 2000, message = "rawAddress must not exceed 2000 characters")
        String rawAddress,

        String countryHint,

        String locale) {

    public EnrichmentHttpRequest {
        if (countryHint == null) countryHint = "";
        if (locale == null) locale = "";
    }
}

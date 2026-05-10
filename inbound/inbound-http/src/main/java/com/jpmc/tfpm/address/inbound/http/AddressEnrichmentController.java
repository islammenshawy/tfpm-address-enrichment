package com.jpmc.tfpm.address.inbound.http;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.RawAddress;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST endpoint for address enrichment. Thin adapter — all logic in the service.
 */
@RestController
@RequestMapping("/api/v1")
public class AddressEnrichmentController {

    private final AddressEnrichmentService service;

    public AddressEnrichmentController(AddressEnrichmentService service) {
        this.service = service;
    }

    @PostMapping("/enrich")
    public ResponseEntity<EnrichmentHttpResponse> enrich(
            @RequestBody EnrichmentHttpRequest body,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        var rawAddress = new RawAddress(body.rawAddress(), body.countryHint(), body.locale());
        var request = new EnrichmentRequest(correlationId, EnrichmentRequest.SourceChannel.HTTP, rawAddress);

        var result = service.enrich(request);
        var response = EnrichmentHttpResponse.from(result);

        return switch (result.outcome()) {
            case SUCCESS, PERSISTED_DUPLICATE -> ResponseEntity.ok(response);
            case REQUIRES_REVIEW -> ResponseEntity.status(HttpStatus.OK).body(response);
            case UNSTRUCTURABLE -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        };
    }
}

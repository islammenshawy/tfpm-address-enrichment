package com.jpmc.tfpm.address.inbound.http;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.ExceptionQueue;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.ResultPersistence;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoint for address enrichment. Thin adapter — all logic in the service.
 */
@RestController
@RequestMapping("/api/v1")
public class AddressEnrichmentController {

    private final AddressEnrichmentService service;
    private final ResultPersistence resultPersistence;
    private final ExceptionQueue exceptionQueue;

    public AddressEnrichmentController(
            AddressEnrichmentService service,
            ResultPersistence resultPersistence,
            ExceptionQueue exceptionQueue) {
        this.service = service;
        this.resultPersistence = resultPersistence;
        this.exceptionQueue = exceptionQueue;
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

    @PostMapping("/enrich/batch")
    public ResponseEntity<List<EnrichmentHttpResponse>> enrichBatch(
            @RequestBody List<EnrichmentHttpRequest> batch,
            @RequestHeader(value = "X-Correlation-Id", required = false) String batchCorrelationId) {

        if (batch.size() > 100) {
            return ResponseEntity.badRequest().build();
        }

        if (batchCorrelationId == null || batchCorrelationId.isBlank()) {
            batchCorrelationId = UUID.randomUUID().toString();
        }

        var results = new ArrayList<EnrichmentHttpResponse>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            var item = batch.get(i);
            var correlationId = batchCorrelationId + "/" + i;
            var rawAddress = new RawAddress(item.rawAddress(), item.countryHint(), item.locale());
            var request = new EnrichmentRequest(correlationId, EnrichmentRequest.SourceChannel.HTTP, rawAddress);
            var result = service.enrich(request);
            results.add(EnrichmentHttpResponse.from(result));
        }

        return ResponseEntity.ok(results);
    }

    @GetMapping("/results/{resultId}")
    public ResponseEntity<EnrichmentHttpResponse> getResult(
            @PathVariable long resultId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        var result = resultPersistence.loadResult(resultId, correlationId);
        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(EnrichmentHttpResponse.from(result.get()));
    }

    @PostMapping("/replay/{exceptionId}")
    public ResponseEntity<Map<String, Object>> replayException(
            @PathVariable long exceptionId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        var resolvedBy = body != null ? body.getOrDefault("resolvedBy", "system") : "system";
        var resolutionJson = body != null ? body.getOrDefault("resolutionJson", "{}") : "{}";
        int expectedVersion = body != null
                ? Integer.parseInt(body.getOrDefault("expectedVersion", "1"))
                : 1;

        var resolved = exceptionQueue.resolve(exceptionId, resolvedBy, resolutionJson, expectedVersion);

        if (!resolved) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Version conflict — exception may have been updated by another operator",
                            "exceptionId", exceptionId));
        }

        return ResponseEntity.ok(Map.of(
                "exceptionId", exceptionId,
                "status", "RESOLVED",
                "resolvedBy", resolvedBy));
    }
}

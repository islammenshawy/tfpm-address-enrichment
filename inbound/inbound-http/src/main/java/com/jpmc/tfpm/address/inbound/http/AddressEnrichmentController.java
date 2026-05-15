package com.jpmc.tfpm.address.inbound.http;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.ExceptionQueue;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.ResultPersistence;

import com.jpmc.tfpm.address.domain.ThreadSafe;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoint for address enrichment. Thin adapter — all logic in the service.
 */
@ThreadSafe
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
            @Valid @RequestBody EnrichmentHttpRequest body,
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

    // TODO [P2-10]: Batch processing is sequential and can exceed the 500ms pipeline
    // budget when the batch approaches the 100-item maximum. A future iteration should
    // either parallelize enrichment calls (e.g., via a bounded virtual-thread executor)
    // or assign the batch endpoint a separate, longer SLA distinct from the single-address
    // pipeline budget. Do not change behavior without revisiting backpressure and
    // thread-safety implications.
    @PostMapping("/enrich/batch")
    public ResponseEntity<List<EnrichmentHttpResponse>> enrichBatch(
            @Valid @RequestBody List<EnrichmentHttpRequest> batch,
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

        var loadResult = resultPersistence.loadResult(resultId, correlationId);
        if (loadResult.isFailure()) {
            return ResponseEntity.internalServerError().build();
        }
        var result = loadResult.getOrThrow();
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        var errors = new ArrayList<Map<String, String>>();
        for (var fieldError : ex.getBindingResult().getFieldErrors()) {
            var errorDetail = new LinkedHashMap<String, String>();
            errorDetail.put("field", fieldError.getField());
            errorDetail.put("message", fieldError.getDefaultMessage());
            errors.add(errorDetail);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "urn:tfpm:address:error:validation");
        body.put("title", "Bad Request");
        body.put("status", 400);
        body.put("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }
}

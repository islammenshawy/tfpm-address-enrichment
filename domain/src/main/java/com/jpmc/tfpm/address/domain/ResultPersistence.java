package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;

import java.util.Optional;

/**
 * Persistence port for enrichment results. Implemented by
 * adapter-oracle-app in Phase 2.
 */
public interface ResultPersistence {

    /**
     * Persist a cascade result and return the generated RESULT_ID.
     *
     * @param request        the enrichment request
     * @param cascadeResult  the cascade result to persist
     * @param requiresReview whether the service determined the result requires human review
     */
    Result<Long> persistResult(EnrichmentRequest request, CascadeResult cascadeResult, boolean requiresReview);

    /**
     * Load a previously persisted result by its row id.
     */
    Result<Optional<EnrichmentResult>> loadResult(long resultRowId, String correlationId);

    /**
     * Write a row to the EXCEPTION_QUEUE for human review.
     *
     * @param resultRowId the RESULT_ID (may be 0 if no result was persisted)
     * @param reason      e.g. "LOW_CONFIDENCE", "MISSING_REQUIRED", "UNSTRUCTURABLE"
     */
    void writeToExceptionQueue(long resultRowId, String reason);
}

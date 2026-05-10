package com.jpmc.tfpm.address.domain;

/**
 * Port for the accuracy sampling operation. Implementation lives in the
 * Oracle adapter module where jOOQ access is permitted.
 */
public interface AccuracySampler {

    /**
     * Sample production results for accuracy review.
     * Stratified: 4 HIGH, 4 MID, 2 LOW confidence per Tier-0 country.
     *
     * @return total number of samples queued for review
     */
    int sampleForReview();
}

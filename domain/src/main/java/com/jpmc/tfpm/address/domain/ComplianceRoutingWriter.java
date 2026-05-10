package com.jpmc.tfpm.address.domain;

/**
 * Port for persisting compliance routing decisions to the COMPLIANCE_ROUTING table.
 */
public interface ComplianceRoutingWriter {

    void record(long resultId, String countryHint, ComplianceDecision decision, String correlationId);
}

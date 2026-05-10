package com.jpmc.tfpm.address.adapter.prowide;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import com.prowidesoftware.swift.model.mx.MxPacs00800112;
import com.prowidesoftware.swift.model.mx.dic.PostalAddress27;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Enriches pacs.008 MX messages by structuring unstructured PstlAdr blocks.
 * Parses the XML, finds all PostalAddress27 blocks, enriches those that
 * have only AdrLine (unstructured), and re-injects the structured fields.
 *
 * <p>Thread-safe: no mutable state. Marshallers/unmarshallers are created
 * per call since JAXB marshallers are not thread-safe.
 */
@ThreadSafe
public final class MxMessageEnricher {

    private static final Logger LOG = LoggerFactory.getLogger(MxMessageEnricher.class);

    private final ProwideAddressMapper mapper;

    public MxMessageEnricher(ProwideAddressMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Parse a pacs.008.001.12 XML message, enrich unstructured addresses,
     * and return the enriched XML.
     *
     * @param xml          the raw pacs.008 XML string
     * @param enricher     the enrichment service to call for each address
     * @param correlationId for logging
     * @return the enriched XML string, or the original if no enrichment was needed
     */
    public String enrich(String xml, AddressEnrichmentService enricher, String correlationId) {
        try {
            var mx = MxPacs00800112.parse(xml);
            if (mx == null || mx.getFIToFICstmrCdtTrf() == null) {
                LOG.debug("Not a valid pacs.008 or empty [corrId={}]", correlationId);
                return xml;
            }

            var cdtTrfTxInfs = mx.getFIToFICstmrCdtTrf().getCdtTrfTxInf();
            if (cdtTrfTxInfs == null || cdtTrfTxInfs.isEmpty()) return xml;

            boolean anyEnriched = false;

            for (var txInf : cdtTrfTxInfs) {
                // Debtor address
                if (txInf.getDbtr() != null && txInf.getDbtr().getPstlAdr() != null) {
                    if (enrichAddress(txInf.getDbtr().getPstlAdr(), enricher, correlationId + "/Dbtr")) {
                        anyEnriched = true;
                    }
                }
                // Creditor address
                if (txInf.getCdtr() != null && txInf.getCdtr().getPstlAdr() != null) {
                    if (enrichAddress(txInf.getCdtr().getPstlAdr(), enricher, correlationId + "/Cdtr")) {
                        anyEnriched = true;
                    }
                }
            }

            if (!anyEnriched) {
                LOG.debug("No addresses needed enrichment [corrId={}]", correlationId);
                return xml;
            }

            return mx.message();

        } catch (Exception e) {
            LOG.error("Failed to enrich pacs.008 [corrId={}]", correlationId, e);
            return xml;
        }
    }

    private boolean enrichAddress(PostalAddress27 pstlAdr, AddressEnrichmentService enricher,
                                  String correlationId) {
        // Skip if already structured (has TwnNm and Ctry)
        if (isAlreadyStructured(pstlAdr)) {
            LOG.debug("Address already structured, skipping [corrId={}]", correlationId);
            return false;
        }

        // Extract raw from AdrLine
        var rawText = extractRawText(pstlAdr);
        if (rawText.isBlank()) return false;

        var countryHint = pstlAdr.getCtry() != null ? pstlAdr.getCtry() : "";
        var request = new EnrichmentRequest(correlationId,
                EnrichmentRequest.SourceChannel.HTTP, new RawAddress(rawText, countryHint, ""));

        var result = enricher.enrich(request);
        if (!result.isSuccess()) return false;

        // Inject structured fields back
        var structured = result.structuredAddress();
        structured.get(AddressField.CTRY).ifPresent(fv -> pstlAdr.setCtry(fv.value()));
        structured.get(AddressField.TWN_NM).ifPresent(fv -> pstlAdr.setTwnNm(fv.value()));
        structured.get(AddressField.PST_CD).ifPresent(fv -> pstlAdr.setPstCd(fv.value()));
        structured.get(AddressField.CTRY_SUB_DVSN).ifPresent(fv -> pstlAdr.setCtrySubDvsn(fv.value()));
        structured.get(AddressField.STRT_NM).ifPresent(fv -> pstlAdr.setStrtNm(fv.value()));
        structured.get(AddressField.BLDG_NB).ifPresent(fv -> pstlAdr.setBldgNb(fv.value()));
        structured.get(AddressField.BLDG_NM).ifPresent(fv -> pstlAdr.setBldgNm(fv.value()));

        LOG.info("Enriched address with {} fields [corrId={}]",
                structured.fields().size(), correlationId);
        return true;
    }

    private boolean isAlreadyStructured(PostalAddress27 addr) {
        return addr.getTwnNm() != null && !addr.getTwnNm().isBlank()
                && addr.getCtry() != null && !addr.getCtry().isBlank();
    }

    private String extractRawText(PostalAddress27 addr) {
        var lines = addr.getAdrLine();
        if (lines == null || lines.isEmpty()) return "";
        return String.join(", ", lines);
    }
}

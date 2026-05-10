package com.jpmc.tfpm.address.adapter.prowide;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import com.prowidesoftware.swift.model.mx.dic.PostalAddress27;

/**
 * Maps between domain {@link StructuredAddress} and Prowide's
 * ISO 20022 {@link PostalAddress27}. Creates fresh Prowide objects
 * per call since they are mutable POJOs.
 */
@ThreadSafe
public final class ProwideAddressMapper {

    /**
     * Convert domain StructuredAddress to ISO 20022 PostalAddress27.
     */
    public PostalAddress27 toIso20022(StructuredAddress address) {
        var pstlAdr = new PostalAddress27();

        address.get(AddressField.CTRY).ifPresent(fv -> pstlAdr.setCtry(fv.value()));
        address.get(AddressField.TWN_NM).ifPresent(fv -> pstlAdr.setTwnNm(fv.value()));
        address.get(AddressField.PST_CD).ifPresent(fv -> pstlAdr.setPstCd(fv.value()));
        address.get(AddressField.CTRY_SUB_DVSN).ifPresent(fv -> pstlAdr.setCtrySubDvsn(fv.value()));
        address.get(AddressField.STRT_NM).ifPresent(fv -> pstlAdr.setStrtNm(fv.value()));
        address.get(AddressField.BLDG_NB).ifPresent(fv -> pstlAdr.setBldgNb(fv.value()));
        address.get(AddressField.BLDG_NM).ifPresent(fv -> pstlAdr.setBldgNm(fv.value()));
        address.get(AddressField.ADR_LINE).ifPresent(fv -> pstlAdr.getAdrLine().add(fv.value()));

        return pstlAdr;
    }

    /**
     * Convert ISO 20022 PostalAddress27 back to domain StructuredAddress.
     * Confidence defaults to 1.0 since this is a known-good source.
     */
    public StructuredAddress fromIso20022(PostalAddress27 pstlAdr) {
        var builder = StructuredAddress.builder();

        if (pstlAdr.getCtry() != null && !pstlAdr.getCtry().isBlank()) {
            builder.put(AddressField.CTRY, pstlAdr.getCtry(), 1.0);
        }
        if (pstlAdr.getTwnNm() != null && !pstlAdr.getTwnNm().isBlank()) {
            builder.put(AddressField.TWN_NM, pstlAdr.getTwnNm(), 1.0);
        }
        if (pstlAdr.getPstCd() != null && !pstlAdr.getPstCd().isBlank()) {
            builder.put(AddressField.PST_CD, pstlAdr.getPstCd(), 1.0);
        }
        if (pstlAdr.getCtrySubDvsn() != null && !pstlAdr.getCtrySubDvsn().isBlank()) {
            builder.put(AddressField.CTRY_SUB_DVSN, pstlAdr.getCtrySubDvsn(), 1.0);
        }
        if (pstlAdr.getStrtNm() != null && !pstlAdr.getStrtNm().isBlank()) {
            builder.put(AddressField.STRT_NM, pstlAdr.getStrtNm(), 1.0);
        }
        if (pstlAdr.getBldgNb() != null && !pstlAdr.getBldgNb().isBlank()) {
            builder.put(AddressField.BLDG_NB, pstlAdr.getBldgNb(), 1.0);
        }
        if (pstlAdr.getBldgNm() != null && !pstlAdr.getBldgNm().isBlank()) {
            builder.put(AddressField.BLDG_NM, pstlAdr.getBldgNm(), 1.0);
        }
        if (pstlAdr.getAdrLine() != null && !pstlAdr.getAdrLine().isEmpty()) {
            builder.put(AddressField.ADR_LINE, String.join(", ", pstlAdr.getAdrLine()), 1.0);
        }

        return builder.build();
    }
}

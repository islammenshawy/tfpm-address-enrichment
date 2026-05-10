package com.jpmc.tfpm.address.adapter.prowide;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.prowidesoftware.swift.model.mx.dic.PostalAddress27;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProwideAddressMapper")
class ProwideAddressMapperTest {

    private ProwideAddressMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ProwideAddressMapper();
    }

    @Test
    void toIso20022_maps_all_fields() {
        var address = StructuredAddress.builder()
                .put(AddressField.CTRY, "GB", 0.99)
                .put(AddressField.TWN_NM, "London", 0.95)
                .put(AddressField.PST_CD, "SW1A 1AA", 0.90)
                .put(AddressField.CTRY_SUB_DVSN, "England", 0.88)
                .put(AddressField.STRT_NM, "Downing Street", 0.92)
                .put(AddressField.BLDG_NB, "10", 0.95)
                .put(AddressField.BLDG_NM, "Number 10", 0.85)
                .build();

        var pstlAdr = mapper.toIso20022(address);

        assertThat(pstlAdr.getCtry()).isEqualTo("GB");
        assertThat(pstlAdr.getTwnNm()).isEqualTo("London");
        assertThat(pstlAdr.getPstCd()).isEqualTo("SW1A 1AA");
        assertThat(pstlAdr.getCtrySubDvsn()).isEqualTo("England");
        assertThat(pstlAdr.getStrtNm()).isEqualTo("Downing Street");
        assertThat(pstlAdr.getBldgNb()).isEqualTo("10");
        assertThat(pstlAdr.getBldgNm()).isEqualTo("Number 10");
    }

    @Test
    void toIso20022_handles_partial_address() {
        var address = StructuredAddress.builder()
                .put(AddressField.CTRY, "US", 0.99)
                .put(AddressField.TWN_NM, "New York", 0.90)
                .build();

        var pstlAdr = mapper.toIso20022(address);

        assertThat(pstlAdr.getCtry()).isEqualTo("US");
        assertThat(pstlAdr.getTwnNm()).isEqualTo("New York");
        assertThat(pstlAdr.getPstCd()).isNull();
        assertThat(pstlAdr.getStrtNm()).isNull();
    }

    @Test
    void toIso20022_handles_empty_address() {
        var pstlAdr = mapper.toIso20022(StructuredAddress.empty());

        assertThat(pstlAdr.getCtry()).isNull();
        assertThat(pstlAdr.getTwnNm()).isNull();
    }

    @Test
    void fromIso20022_maps_all_fields() {
        var pstlAdr = new PostalAddress27();
        pstlAdr.setCtry("DE");
        pstlAdr.setTwnNm("Berlin");
        pstlAdr.setPstCd("10117");
        pstlAdr.setStrtNm("Friedrichstraße");
        pstlAdr.setBldgNb("43");

        var address = mapper.fromIso20022(pstlAdr);

        assertThat(address.get(AddressField.CTRY).get().value()).isEqualTo("DE");
        assertThat(address.get(AddressField.TWN_NM).get().value()).isEqualTo("Berlin");
        assertThat(address.get(AddressField.PST_CD).get().value()).isEqualTo("10117");
        assertThat(address.get(AddressField.STRT_NM).get().value()).isEqualTo("Friedrichstraße");
        assertThat(address.get(AddressField.BLDG_NB).get().value()).isEqualTo("43");
    }

    @Test
    void fromIso20022_skips_null_fields() {
        var pstlAdr = new PostalAddress27();
        pstlAdr.setCtry("US");

        var address = mapper.fromIso20022(pstlAdr);

        assertThat(address.get(AddressField.CTRY)).isPresent();
        assertThat(address.get(AddressField.TWN_NM)).isEmpty();
        assertThat(address.get(AddressField.PST_CD)).isEmpty();
    }

    @Test
    void fromIso20022_assigns_confidence_1() {
        var pstlAdr = new PostalAddress27();
        pstlAdr.setCtry("JP");
        pstlAdr.setTwnNm("Tokyo");

        var address = mapper.fromIso20022(pstlAdr);

        assertThat(address.get(AddressField.CTRY).get().confidence()).isEqualTo(1.0);
        assertThat(address.get(AddressField.TWN_NM).get().confidence()).isEqualTo(1.0);
    }

    @Test
    void round_trip_preserves_fields() {
        var original = StructuredAddress.builder()
                .put(AddressField.CTRY, "CH", 0.99)
                .put(AddressField.TWN_NM, "Zürich", 0.95)
                .put(AddressField.PST_CD, "8001", 0.90)
                .put(AddressField.STRT_NM, "Bahnhofstrasse", 0.88)
                .put(AddressField.BLDG_NB, "1", 0.92)
                .build();

        var pstlAdr = mapper.toIso20022(original);
        var roundTripped = mapper.fromIso20022(pstlAdr);

        // Values are preserved, confidence becomes 1.0 (fromIso20022 default)
        assertThat(roundTripped.get(AddressField.CTRY).get().value()).isEqualTo("CH");
        assertThat(roundTripped.get(AddressField.TWN_NM).get().value()).isEqualTo("Zürich");
        assertThat(roundTripped.get(AddressField.PST_CD).get().value()).isEqualTo("8001");
        assertThat(roundTripped.get(AddressField.STRT_NM).get().value()).isEqualTo("Bahnhofstrasse");
        assertThat(roundTripped.get(AddressField.BLDG_NB).get().value()).isEqualTo("1");
    }
}

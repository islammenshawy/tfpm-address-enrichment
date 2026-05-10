package com.jpmc.tfpm.address.adapter.libpostal;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.RawAddress;

import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LibpostalAddressStructurer")
class LibpostalAddressStructurerTest {

    // Use a channel to a non-existent endpoint — structure() will catch the error
    private final LibpostalAddressStructurer structurer = new LibpostalAddressStructurer(
            ManagedChannelBuilder.forTarget("localhost:0")
                    .usePlaintext()
                    .build(),
            100);

    @Test
    void name_is_libpostal() {
        assertThat(structurer.name()).isEqualTo("libpostal");
    }

    @Test
    void supported_fields_are_correct() {
        assertThat(structurer.supportedFields()).containsExactlyInAnyOrder(
                AddressField.CTRY, AddressField.TWN_NM, AddressField.PST_CD,
                AddressField.CTRY_SUB_DVSN, AddressField.STRT_NM, AddressField.BLDG_NB);
        assertThat(structurer.supportedFields()).doesNotContain(AddressField.BLDG_NM, AddressField.ADR_LINE);
    }

    @Test
    void unreachable_sidecar_returns_empty_result() {
        var result = structurer.structure(RawAddress.of("123 Main St, New York"));

        assertThat(result.structurerName()).isEqualTo("libpostal");
        assertThat(result.fields()).isEmpty();
        assertThat(result.latency()).isNotNull();
    }

    @Test
    void empty_input_returns_empty_result() {
        var result = structurer.structure(RawAddress.of(""));

        assertThat(result.structurerName()).isEqualTo("libpostal");
        assertThat(result.fields()).isEmpty();
    }
}

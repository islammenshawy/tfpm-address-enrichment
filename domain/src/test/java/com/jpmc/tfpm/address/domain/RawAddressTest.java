package com.jpmc.tfpm.address.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RawAddress")
class RawAddressTest {

    @Test
    void valid_construction() {
        var addr = new RawAddress("123 Main St, City", "US", "en-US");
        assertThat(addr.raw()).isEqualTo("123 Main St, City");
        assertThat(addr.countryHint()).isEqualTo("US");
        assertThat(addr.locale()).isEqualTo("en-US");
    }

    @Test
    void of_convenience_factory() {
        var addr = RawAddress.of("123 Main St");
        assertThat(addr.raw()).isEqualTo("123 Main St");
        assertThat(addr.countryHint()).isEmpty();
        assertThat(addr.locale()).isEmpty();
    }

    @Test
    void empty_country_hint_allowed() {
        var addr = new RawAddress("addr", "", "");
        assertThat(addr.countryHint()).isEmpty();
    }

    @Test
    void canonical_trims_and_lowercases() {
        var addr = RawAddress.of("  Hello   World  ");
        assertThat(addr.canonical()).isEqualTo("hello world");
    }

    @Test
    void canonical_collapses_whitespace() {
        var addr = RawAddress.of("123\t\tMain\n\nSt");
        assertThat(addr.canonical()).isEqualTo("123 main st");
    }

    @Test
    void canonical_of_empty_string() {
        var addr = RawAddress.of("");
        assertThat(addr.canonical()).isEmpty();
    }

    @Test
    void rejects_raw_over_2000_chars() {
        var longRaw = "x".repeat(2001);
        assertThatThrownBy(() -> RawAddress.of(longRaw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2000 chars");
    }

    @Test
    void accepts_raw_exactly_2000_chars() {
        var raw = "x".repeat(2000);
        var addr = RawAddress.of(raw);
        assertThat(addr.raw()).hasSize(2000);
    }

    @Test
    void rejects_country_hint_with_wrong_length() {
        assertThatThrownBy(() -> new RawAddress("addr", "USA", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alpha-2");
    }

    @Test
    void rejects_single_char_country_hint() {
        assertThatThrownBy(() -> new RawAddress("addr", "U", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null_raw() {
        assertThatThrownBy(() -> new RawAddress(null, "", ""))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_null_country_hint() {
        assertThatThrownBy(() -> new RawAddress("addr", null, ""))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_null_locale() {
        assertThatThrownBy(() -> new RawAddress("addr", "", null))
                .isInstanceOf(NullPointerException.class);
    }
}

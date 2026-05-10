package com.jpmc.tfpm.address.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CountryRouter")
class CountryRouterTest {

    @Test
    void noOp_returns_empty_list_for_any_country() {
        var router = CountryRouter.noOp();
        assertThat(router.structurersFor("US")).isEmpty();
        assertThat(router.structurersFor("AE")).isEmpty();
        assertThat(router.structurersFor("CN")).isEmpty();
        assertThat(router.structurersFor("")).isEmpty();
    }

    @Test
    void noOp_rejects_null_country_hint() {
        var router = CountryRouter.noOp();
        assertThatThrownBy(() -> router.structurersFor(null))
                .isInstanceOf(NullPointerException.class);
    }
}

package com.jpmc.tfpm.address.app.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConfigDrivenCountryRouter")
class ConfigDrivenCountryRouterTest {

    @Test
    void cn_routes_to_llm_only() {
        var router = new ConfigDrivenCountryRouter(Map.of("CN", List.of("llm")));
        assertThat(router.structurersFor("CN")).containsExactly("llm");
    }

    @Test
    void unknown_country_returns_empty_list() {
        var router = new ConfigDrivenCountryRouter(Map.of("CN", List.of("llm")));
        assertThat(router.structurersFor("US")).isEmpty();
    }

    @Test
    void empty_hint_returns_empty_list() {
        var router = new ConfigDrivenCountryRouter(Map.of("CN", List.of("llm")));
        assertThat(router.structurersFor("")).isEmpty();
    }

    @Test
    void case_insensitive_lookup() {
        var router = new ConfigDrivenCountryRouter(Map.of("cn", List.of("llm")));
        assertThat(router.structurersFor("CN")).containsExactly("llm");
    }

    @Test
    void multiple_structurers_preserved_in_order() {
        var router = new ConfigDrivenCountryRouter(Map.of("JP", List.of("llm", "swift-crf")));
        assertThat(router.structurersFor("JP")).containsExactly("llm", "swift-crf");
    }

    @Test
    void empty_config_returns_empty_for_all() {
        var router = new ConfigDrivenCountryRouter(Map.of());
        assertThat(router.structurersFor("US")).isEmpty();
    }
}

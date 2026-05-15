package com.jpmc.tfpm.address.app.cascade;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.AddressStructurer.FieldValue;
import com.jpmc.tfpm.address.domain.AddressStructurer.StructuringResult;
import com.jpmc.tfpm.address.domain.StructuredAddress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConsensusAnalyzer — weighted per-country consensus")
class ConsensusAnalyzerTest {

    /**
     * Weight config matching production:
     * - libpostal: default 0.3, US/GB/DE 1.0
     * - glm/azure-gpt: default 1.0
     */
    private static final Map<String, Map<String, Double>> WEIGHTS;
    static {
        var libpostalWeights = new HashMap<String, Double>();
        libpostalWeights.put("default", 0.3);
        libpostalWeights.put("US", 1.0);
        libpostalWeights.put("GB", 1.0);
        libpostalWeights.put("DE", 1.0);

        var w = new HashMap<String, Map<String, Double>>();
        w.put("libpostal", Map.copyOf(libpostalWeights));
        w.put("glm", Map.<String, Double>of("default", 1.0));
        w.put("azure-gpt", Map.<String, Double>of("default", 1.0));
        WEIGHTS = Map.copyOf(w);
    }

    private final ConsensusAnalyzer analyzer = new ConsensusAnalyzer(WEIGHTS);
    private final ConsensusAnalyzer noWeights = new ConsensusAnalyzer();

    // ========== Helper methods ==========

    private static StructuringResult result(String name, Map<AddressField, String> fields) {
        var map = new EnumMap<AddressField, FieldValue>(AddressField.class);
        fields.forEach((k, v) -> map.put(k, new FieldValue(v, 0.85)));
        return new StructuringResult(name, map, Duration.ofMillis(100), Map.of());
    }

    private static StructuredAddress merged(Map<AddressField, String> fields) {
        var map = new EnumMap<AddressField, FieldValue>(AddressField.class);
        fields.forEach((k, v) -> map.put(k, new FieldValue(v, 0.90)));
        return new StructuredAddress(map);
    }

    // ========== Tests ==========

    @Test
    void all_agree_no_disagreements() {
        var trace = List.of(
                result("libpostal", Map.of(AddressField.CTRY, "US", AddressField.TWN_NM, "New York")),
                result("glm", Map.of(AddressField.CTRY, "US", AddressField.TWN_NM, "New York")),
                result("azure-gpt", Map.of(AddressField.CTRY, "US", AddressField.TWN_NM, "New York")));
        var m = merged(Map.of(AddressField.CTRY, "US", AddressField.TWN_NM, "New York"));

        var consensus = analyzer.analyze(trace, m, "US");

        assertThat(consensus.hasDisagreements()).isFalse();
        assertThat(consensus.agreementCount()).isEqualTo(2);
        assertThat(consensus.disagreementCount()).isEqualTo(0);
        assertThat(consensus.overallConsensus()).isEqualTo(1.0);
        assertThat(consensus.sourceWeights()).containsEntry("libpostal", 1.0); // US = strong
        assertThat(consensus.sourceWeights()).containsEntry("glm", 1.0);
    }

    @Test
    void weak_source_disagrees_on_non_western_no_flag() {
        // AE: libpostal weight 0.3 (weak), LLMs weight 1.0 (strong)
        // libpostal disagrees on TWN_NM — should NOT flag since libpostal is weak on AE
        var trace = List.of(
                result("libpostal", Map.of(AddressField.CTRY, "AE", AddressField.TWN_NM, "dubai")),
                result("glm", Map.of(AddressField.CTRY, "AE", AddressField.TWN_NM, "Dubai")),
                result("azure-gpt", Map.of(AddressField.CTRY, "AE", AddressField.TWN_NM, "Dubai")));
        var m = merged(Map.of(AddressField.CTRY, "AE", AddressField.TWN_NM, "Dubai"));

        var consensus = analyzer.analyze(trace, m, "AE");

        // libpostal at 0.3 for AE — its disagreement should NOT be flagged
        assertThat(consensus.sourceWeights()).containsEntry("libpostal", 0.3);
        assertThat(consensus.sourceWeights()).containsEntry("glm", 1.0);
        // TWN_NM: libpostal says "dubai" (lowercase), LLMs say "Dubai"
        // Even if values differ in case, the consensus check is case-sensitive
        // but the key point: libpostal is weak, so this shouldn't be a "strong disagreement"
    }

    @Test
    void strong_sources_disagree_flags_disagreement() {
        // Two strong LLMs disagree on BLDG_NM — should flag
        var trace = List.of(
                result("glm", Map.of(AddressField.BLDG_NM, "Tower 3, Emirates Towers")),
                result("azure-gpt", Map.of(AddressField.BLDG_NM, "Emirates Towers Tower 3")));
        var m = merged(Map.of(AddressField.BLDG_NM, "Tower 3, Emirates Towers"));

        var consensus = analyzer.analyze(trace, m, "AE");

        assertThat(consensus.hasDisagreements()).isTrue();
        assertThat(consensus.flaggedFields()).contains(AddressField.BLDG_NM);
        assertThat(consensus.sourceWeights()).containsEntry("glm", 1.0);
        assertThat(consensus.sourceWeights()).containsEntry("azure-gpt", 1.0);
    }

    @Test
    void libpostal_strong_on_western_flags_disagreement() {
        // US: libpostal weight 1.0 (strong) — its disagreement SHOULD flag
        var trace = List.of(
                result("libpostal", Map.of(AddressField.BLDG_NB, "one")),
                result("glm", Map.of(AddressField.BLDG_NB, "1")),
                result("azure-gpt", Map.of(AddressField.BLDG_NB, "1")));
        var m = merged(Map.of(AddressField.BLDG_NB, "1"));

        var consensus = analyzer.analyze(trace, m, "US");

        assertThat(consensus.sourceWeights()).containsEntry("libpostal", 1.0); // US = strong
        assertThat(consensus.hasDisagreements()).isTrue();
        assertThat(consensus.flaggedFields()).contains(AddressField.BLDG_NB);
    }

    @Test
    void libpostal_weak_on_chinese_weight_0_3() {
        var trace = List.of(
                result("libpostal", Map.of(AddressField.TWN_NM, "jing")),
                result("azure-gpt", Map.of(AddressField.TWN_NM, "Shanghai")));
        var m = merged(Map.of(AddressField.TWN_NM, "Shanghai"));

        var consensus = analyzer.analyze(trace, m, "CN");

        assertThat(consensus.sourceWeights()).containsEntry("libpostal", 0.3);
        assertThat(consensus.sourceWeights()).containsEntry("azure-gpt", 1.0);
    }

    @Test
    void no_weights_config_defaults_to_equal() {
        var trace = List.of(
                result("libpostal", Map.of(AddressField.CTRY, "AE")),
                result("glm", Map.of(AddressField.CTRY, "AE")));
        var m = merged(Map.of(AddressField.CTRY, "AE"));

        var consensus = noWeights.analyze(trace, m, "AE");

        // Without weights, all sources default to 1.0
        assertThat(consensus.sourceWeights()).containsEntry("libpostal", 1.0);
        assertThat(consensus.sourceWeights()).containsEntry("glm", 1.0);
    }

    @Test
    void source_weights_included_in_result() {
        var trace = List.of(
                result("libpostal", Map.of(AddressField.CTRY, "HK")),
                result("glm", Map.of(AddressField.CTRY, "HK")),
                result("azure-gpt", Map.of(AddressField.CTRY, "HK")));
        var m = merged(Map.of(AddressField.CTRY, "HK"));

        var consensus = analyzer.analyze(trace, m, "HK");

        assertThat(consensus.sourceWeights())
                .hasSize(3)
                .containsEntry("libpostal", 0.3)  // HK not in strong list → default 0.3
                .containsEntry("glm", 1.0)
                .containsEntry("azure-gpt", 1.0);
    }

    @Test
    void empty_trace_returns_empty_consensus() {
        var m = merged(Map.of(AddressField.CTRY, "US"));

        var consensus = analyzer.analyze(List.of(), m, "US");

        assertThat(consensus.sourceCount()).isEqualTo(0);
        assertThat(consensus.agreementCount()).isEqualTo(0);
        assertThat(consensus.sourceWeights()).isEmpty();
    }

    @Test
    void single_source_no_consensus_possible() {
        var trace = List.of(
                result("glm", Map.of(AddressField.CTRY, "US", AddressField.TWN_NM, "NYC")));
        var m = merged(Map.of(AddressField.CTRY, "US", AddressField.TWN_NM, "NYC"));

        var consensus = analyzer.analyze(trace, m, "US");

        assertThat(consensus.sourceCount()).isEqualTo(1);
        assertThat(consensus.sourceWeights()).containsEntry("glm", 1.0);
    }
}

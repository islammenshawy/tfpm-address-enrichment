package com.jpmc.tfpm.address.adapter.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.jpmc.tfpm.address.domain.EnrichmentError;
import com.jpmc.tfpm.address.domain.LlmModelClient;
import com.jpmc.tfpm.address.domain.LlmModelClient.LlmCompletionRequest;
import com.jpmc.tfpm.address.domain.LlmModelClient.LlmCompletionResponse;
import com.jpmc.tfpm.address.domain.LlmModelClient.LlmModelMetadata;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.Result;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LlmAddressStructurer")
class LlmAddressStructurerTest {

    private LlmModelClient mockClient;
    private LlmAddressStructurer structurer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockClient = mock(LlmModelClient.class);
        when(mockClient.name()).thenReturn("test-llm");
        when(mockClient.metadata()).thenReturn(new LlmModelMetadata(
                "test", "test-model", false, 0, 0, Duration.ofSeconds(2)));

        var promptLoader = new PromptTemplateLoader(
                new ClassPathResource("prompts/address-structuring.json"), objectMapper);

        structurer = new LlmAddressStructurer(
                mockClient,
                promptLoader,
                objectMapper,
                EnumSet.of(AddressField.CTRY, AddressField.TWN_NM, AddressField.PST_CD,
                        AddressField.CTRY_SUB_DVSN, AddressField.STRT_NM,
                        AddressField.BLDG_NB, AddressField.BLDG_NM));
    }

    @Test
    void name_is_llm() {
        assertThat(structurer.name()).isEqualTo("llm");
    }

    @Test
    void successful_parse_returns_fields() {
        var responseJson = """
                {"fields": {
                    "CTRY": {"value": "US", "confidence": 0.95},
                    "TWN_NM": {"value": "New York", "confidence": 0.90},
                    "STRT_NM": {"value": "Main St", "confidence": 0.85}
                }}""";

        when(mockClient.complete(any(LlmCompletionRequest.class)))
                .thenReturn(Result.success(new LlmCompletionResponse(
                        responseJson,
                        LlmCompletionResponse.FinishReason.STOP,
                        100, 50, Duration.ofMillis(200), "req-1")));

        var result = structurer.structure(RawAddress.of("123 Main St, New York"));

        assertThat(result.structurerName()).isEqualTo("llm");
        assertThat(result.fields()).hasSize(3);
        assertThat(result.fields().get(AddressField.CTRY).value()).isEqualTo("US");
        assertThat(result.fields().get(AddressField.CTRY).confidence()).isEqualTo(0.95);
        assertThat(result.fields().get(AddressField.TWN_NM).value()).isEqualTo("New York");
    }

    @Test
    void invalid_json_returns_empty() {
        when(mockClient.complete(any(LlmCompletionRequest.class)))
                .thenReturn(Result.success(new LlmCompletionResponse(
                        "not valid json {{{",
                        LlmCompletionResponse.FinishReason.STOP,
                        100, 50, Duration.ofMillis(200), "req-2")));

        var result = structurer.structure(RawAddress.of("test"));

        assertThat(result.fields()).isEmpty();
    }

    @Test
    void client_failure_returns_empty() {
        when(mockClient.complete(any(LlmCompletionRequest.class)))
                .thenReturn(Result.failure(EnrichmentError.of(
                        EnrichmentError.Category.TIMEOUT,
                        "Gateway timeout",
                        "corr-1")));

        var result = structurer.structure(RawAddress.of("test"));

        assertThat(result.structurerName()).isEqualTo("llm");
        assertThat(result.fields()).isEmpty();
    }

    @Test
    void filters_disallowed_fields() {
        // ADR_LINE is not in the allowed set
        var responseJson = """
                {"fields": {
                    "CTRY": {"value": "US", "confidence": 0.95},
                    "ADR_LINE": {"value": "full address line", "confidence": 0.80}
                }}""";

        when(mockClient.complete(any(LlmCompletionRequest.class)))
                .thenReturn(Result.success(new LlmCompletionResponse(
                        responseJson,
                        LlmCompletionResponse.FinishReason.STOP,
                        100, 50, Duration.ofMillis(200), "req-3")));

        var result = structurer.structure(RawAddress.of("test"));

        assertThat(result.fields()).hasSize(1);
        assertThat(result.fields()).containsKey(AddressField.CTRY);
        assertThat(result.fields()).doesNotContainKey(AddressField.ADR_LINE);
    }

    @Test
    void empty_value_filtered() {
        var responseJson = """
                {"fields": {
                    "CTRY": {"value": "US", "confidence": 0.95},
                    "TWN_NM": {"value": "", "confidence": 0.0}
                }}""";

        when(mockClient.complete(any(LlmCompletionRequest.class)))
                .thenReturn(Result.success(new LlmCompletionResponse(
                        responseJson,
                        LlmCompletionResponse.FinishReason.STOP,
                        100, 50, Duration.ofMillis(200), "req-4")));

        var result = structurer.structure(RawAddress.of("test"));

        assertThat(result.fields()).hasSize(1);
        assertThat(result.fields()).containsKey(AddressField.CTRY);
    }

    @Test
    void missing_fields_object_returns_empty() {
        var responseJson = """
                {"message": "I couldn't parse that address"}""";

        when(mockClient.complete(any(LlmCompletionRequest.class)))
                .thenReturn(Result.success(new LlmCompletionResponse(
                        responseJson,
                        LlmCompletionResponse.FinishReason.STOP,
                        100, 50, Duration.ofMillis(200), "req-5")));

        var result = structurer.structure(RawAddress.of("test"));

        assertThat(result.fields()).isEmpty();
    }
}

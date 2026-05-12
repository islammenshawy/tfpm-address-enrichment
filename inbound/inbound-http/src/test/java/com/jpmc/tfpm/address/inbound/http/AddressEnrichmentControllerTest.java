package com.jpmc.tfpm.address.inbound.http;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
import com.jpmc.tfpm.address.domain.ExceptionQueue;
import com.jpmc.tfpm.address.domain.ResultPersistence;
import com.jpmc.tfpm.address.domain.StructuredAddress;
import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("AddressEnrichmentController")
class AddressEnrichmentControllerTest {

    private MockMvc mockMvc;
    private AddressEnrichmentService service;
    private ResultPersistence resultPersistence;
    private ExceptionQueue exceptionQueue;

    @BeforeEach
    void setUp() {
        service = mock(AddressEnrichmentService.class);
        resultPersistence = mock(ResultPersistence.class);
        exceptionQueue = mock(ExceptionQueue.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AddressEnrichmentController(service, resultPersistence, exceptionQueue))
                .build();
    }

    @Test
    void returns_200_on_success() throws Exception {
        var result = new EnrichmentResult(
                "corr-1", EnrichmentResult.Outcome.SUCCESS,
                StructuredAddress.builder()
                        .put(AddressField.CTRY, "US", 0.95)
                        .put(AddressField.TWN_NM, "New York", 0.90)
                        .build(),
                0.90, 42L, Instant.now());

        when(service.enrich(any(EnrichmentRequest.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rawAddress": "123 Main St, New York", "countryHint": "US"}
                                """)
                        .header("X-Correlation-Id", "corr-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.correlationId").value("corr-1"))
                .andExpect(jsonPath("$.fields.CTRY.value").value("US"));
    }

    @Test
    void returns_422_on_unstructurable() throws Exception {
        var result = new EnrichmentResult(
                "corr-2", EnrichmentResult.Outcome.UNSTRUCTURABLE,
                StructuredAddress.empty(), 0.0, null, Instant.now());

        when(service.enrich(any(EnrichmentRequest.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rawAddress": "???"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("UNSTRUCTURABLE"));
    }

    @Test
    void generates_correlation_id_when_not_provided() throws Exception {
        var result = new EnrichmentResult(
                "generated", EnrichmentResult.Outcome.SUCCESS,
                StructuredAddress.empty(), 0.0, null, Instant.now());

        when(service.enrich(any(EnrichmentRequest.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rawAddress": "test"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void batch_returns_200_with_multiple_results() throws Exception {
        var result = new EnrichmentResult(
                "batch-0", EnrichmentResult.Outcome.SUCCESS,
                StructuredAddress.empty(), 0.90, 1L, Instant.now());

        when(service.enrich(any(EnrichmentRequest.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/enrich/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"rawAddress": "addr1", "countryHint": "US"},
                                 {"rawAddress": "addr2", "countryHint": "GB"}]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void batch_rejects_over_100_items() throws Exception {
        var items = new StringBuilder("[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) items.append(",");
            items.append("{\"rawAddress\":\"addr").append(i).append("\"}");
        }
        items.append("]");

        mockMvc.perform(post("/api/v1/enrich/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(items.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_result_returns_200_when_found() throws Exception {
        var result = new EnrichmentResult(
                "corr-3", EnrichmentResult.Outcome.SUCCESS,
                StructuredAddress.builder().put(AddressField.CTRY, "US", 0.95).build(),
                0.95, 42L, Instant.now());

        when(resultPersistence.loadResult(eq(42L), anyString()))
                .thenReturn(Optional.of(result));

        mockMvc.perform(get("/api/v1/results/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUCCESS"));
    }

    @Test
    void get_result_returns_404_when_not_found() throws Exception {
        when(resultPersistence.loadResult(eq(999L), anyString()))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/results/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void replay_returns_200_on_success() throws Exception {
        when(exceptionQueue.resolve(eq(1L), eq("operator"), eq("{}"), eq(1)))
                .thenReturn(true);

        mockMvc.perform(post("/api/v1/replay/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resolvedBy": "operator", "resolutionJson": "{}", "expectedVersion": "1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    void replay_returns_409_on_version_conflict() throws Exception {
        when(exceptionQueue.resolve(eq(1L), eq("operator"), eq("{}"), eq(1)))
                .thenReturn(false);

        mockMvc.perform(post("/api/v1/replay/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resolvedBy": "operator", "resolutionJson": "{}", "expectedVersion": "1"}
                                """))
                .andExpect(status().isConflict());
    }
}

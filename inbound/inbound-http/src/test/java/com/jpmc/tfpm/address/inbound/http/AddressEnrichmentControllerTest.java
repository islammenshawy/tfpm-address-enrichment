package com.jpmc.tfpm.address.inbound.http;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.AddressEnrichmentService.EnrichmentResult;
import com.jpmc.tfpm.address.domain.EnrichmentRequest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("AddressEnrichmentController")
class AddressEnrichmentControllerTest {

    private MockMvc mockMvc;
    private AddressEnrichmentService service;

    @BeforeEach
    void setUp() {
        service = mock(AddressEnrichmentService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AddressEnrichmentController(service))
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
}

package com.jpmc.tfpm.address.adapter.libpostal;

import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean registration for the libpostal gRPC adapter.
 * Creates the ManagedChannel, structurer, and calibrator beans.
 */
@Configuration
@ConditionalOnProperty(name = "enrichment.libpostal.enabled", havingValue = "true", matchIfMissing = true)
public class LibpostalAdapterConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel libpostalChannel(
            @Value("${enrichment.libpostal.endpoint:localhost:50051}") String endpoint) {
        return ManagedChannelBuilder.forTarget(endpoint)
                .usePlaintext()
                .build();
    }

    @Bean
    public AddressStructurer libpostalAddressStructurer(
            ManagedChannel libpostalChannel,
            @Value("${enrichment.libpostal.timeout-ms:500}") long timeoutMs) {
        return new LibpostalAddressStructurer(libpostalChannel, timeoutMs);
    }

    @Bean
    public ConfidenceCalibrator libpostalConfidenceCalibrator(
            @Value("${enrichment.libpostal.calibration-csv:}") String csvResource) {
        if (csvResource != null && !csvResource.isBlank()) {
            return new CsvCalibrationLoader("libpostal", csvResource);
        }
        return new LibpostalConfidenceCalibrator();
    }
}

package com.jpmc.tfpm.address.adapter.libpostal;

import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.Calibrated;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;
import com.jpmc.tfpm.address.proto.v1.AddressStructurerServiceGrpc;
import com.jpmc.tfpm.address.proto.v1.StructureRequest;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client to the libpostal sidecar. Speaks {@code proto/structurer.proto v1}.
 */
@ThreadSafe
@Calibrated
public final class LibpostalAddressStructurer implements AddressStructurer {

    private static final Logger LOG = LoggerFactory.getLogger(LibpostalAddressStructurer.class);
    private static final Set<AddressField> SUPPORTED_FIELDS = Collections.unmodifiableSet(EnumSet.of(
            AddressField.CTRY, AddressField.TWN_NM, AddressField.PST_CD,
            AddressField.CTRY_SUB_DVSN, AddressField.STRT_NM, AddressField.BLDG_NB));

    private final ManagedChannel channel;
    private final long timeoutMs;

    public LibpostalAddressStructurer(ManagedChannel channel, long timeoutMs) {
        this.channel = channel;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String name() {
        return "libpostal";
    }

    @Override
    public Set<AddressField> supportedFields() {
        return SUPPORTED_FIELDS;
    }

    @Override
    public StructuringResult structure(RawAddress raw) {
        var start = Instant.now();
        try {
            var stub = AddressStructurerServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);

            var request = StructureRequest.newBuilder()
                    .setRawAddress(raw.raw())
                    .setCountryHint(raw.countryHint())
                    .setLocale(raw.locale())
                    .build();

            var response = stub.structure(request);
            var latency = Duration.between(start, Instant.now());

            var fields = new EnumMap<AddressField, FieldValue>(AddressField.class);
            for (var entry : response.getFieldsMap().entrySet()) {
                try {
                    var field = AddressField.valueOf(entry.getKey());
                    if (supportedFields().contains(field) && !entry.getValue().getValue().isEmpty()) {
                        fields.put(field, new FieldValue(
                                entry.getValue().getValue(),
                                entry.getValue().getConfidence()));
                    }
                } catch (IllegalArgumentException ignored) {
                    // unknown field name from sidecar — skip
                }
            }

            LOG.debug("libpostal returned {} fields in {}ms", fields.size(), latency.toMillis());
            return new StructuringResult(name(), fields, latency,
                    Map.of("version", response.getStructurerVersion()));

        } catch (StatusRuntimeException e) {
            var latency = Duration.between(start, Instant.now());
            LOG.warn("libpostal gRPC error: {} ({}ms)", e.getStatus(), latency.toMillis());
            return StructuringResult.empty(name(), latency);
        } catch (Exception e) {
            var latency = Duration.between(start, Instant.now());
            LOG.error("libpostal unexpected error", e);
            return StructuringResult.empty(name(), latency);
        }
    }
}

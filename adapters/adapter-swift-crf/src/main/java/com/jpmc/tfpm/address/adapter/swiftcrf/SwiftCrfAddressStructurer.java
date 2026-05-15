package com.jpmc.tfpm.address.adapter.swiftcrf;

import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.Calibrated;
import com.jpmc.tfpm.address.domain.RawAddress;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Placeholder implementation of the SWIFT CRF (Conditional Random Field)
 * address structurer.
 *
 * <p>SWIFT publishes an "AI Address Structuring Model" via the Swift
 * Download Centre, gated by an entitlement check. As of project
 * inception, the model has not been downloaded into our environment and
 * its license terms (source-available with redistribution restriction)
 * are still under IS&amp;C review.
 *
 * <p>This stub exists so the cascade machinery, plugin contract, and
 * configuration knobs are all wired up end-to-end from day one. Activating
 * it later requires zero changes to any other module.
 *
 * <h2>Activation checklist (once the model is available)</h2>
 *
 * <ol>
 *   <li>Confirm IS&amp;C clearance on the SWIFT model license terms.
 *   <li>Wrap the SWIFT runtime (Python/PyTorch typically) in a gRPC
 *       sidecar container that speaks {@code proto/structurer.proto v1}.
 *       Reuse the structure of the libpostal sidecar — only the inference
 *       call differs.
 *   <li>Replace the body of {@link #structure(RawAddress)} with a real
 *       gRPC client call. Clone the pattern from
 *       {@code LibpostalAddressStructurer}.
 *   <li>Implement {@code SwiftCrfConfidenceCalibrator} (start with identity).
 *   <li>Add {@code enrichment.swift-crf.endpoint} and related config
 *       to {@code application.yml}.
 *   <li>Update {@code enrichment.cascade.order} to insert {@code swift-crf}
 *       at the desired position (typically between libpostal and llm).
 *   <li>Set {@code enrichment.swift-crf.enabled: true}.
 *   <li>Run the accuracy harness; confirm no per-country regression.
 *   <li>Deploy the sidecar alongside the app via the existing Helm chart.
 * </ol>
 *
 * <p>Estimated time when bits are ready: half a day.
 *
 * <h2>Why this stub is not just absent</h2>
 *
 * <p>Having the bean class, the config flag, the supported-fields
 * declaration, and the package structure all present today means:
 * <ul>
 *   <li>The plugin contract gets exercised by ArchUnit tests now.
 *   <li>Configuration validation catches typos in cascade order strings now.
 *   <li>The future activation is mechanical (drop in implementation, flip
 *       a flag) rather than architectural (build a new module).
 * </ul>
 */
@Component
@ThreadSafe
@Calibrated
@ConditionalOnProperty(
        name = "enrichment.swift-crf.enabled",
        havingValue = "true")
public final class SwiftCrfAddressStructurer implements AddressStructurer {

    private static final Logger LOG = LoggerFactory.getLogger(SwiftCrfAddressStructurer.class);

    @Override
    public String name() {
        return "swift-crf";
    }

    @Override
    public Set<AddressField> supportedFields() {
        // Per SWIFT documentation, the CRF model targets these fields.
        // Full supported set will be confirmed against the actual model once
        // it lands. Conservative subset here ensures the FieldMerger does not
        // accept fields the model cannot reliably produce.
        return EnumSet.of(
                AddressField.CTRY,
                AddressField.TWN_NM,
                AddressField.PST_CD,
                AddressField.CTRY_SUB_DVSN);
    }

    @Override
    public StructuringResult structure(RawAddress raw) {
        LOG.warn("SwiftCrfAddressStructurer is a stub — returning empty result. "
                + "The SWIFT model has not been downloaded and IS&C-cleared yet. "
                + "Set enrichment.swift-crf.enabled=false (the default) until "
                + "the activation checklist in this class' Javadoc is complete.");
        return StructuringResult.empty(name(), Duration.ZERO);
    }
}

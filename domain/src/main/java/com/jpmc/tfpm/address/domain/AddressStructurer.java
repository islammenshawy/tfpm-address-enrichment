package com.jpmc.tfpm.address.domain;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single extensibility point of the enrichment service.
 *
 * <p>Every address-structuring strategy — libpostal, the SWIFT CRF, the LLM
 * gateway, future vendor products — implements this interface. The cascade
 * orchestrator depends only on {@code List<AddressStructurer>}; adding a new
 * implementation is a pure addition with zero changes to existing code.
 *
 * <h2>Thread-safety</h2>
 *
 * <p>Every implementation MUST be annotated {@link ThreadSafe} and MUST
 * satisfy the contract that annotation specifies. The service runs as N
 * replicas with three concurrent input channels (HTTP, Kafka, IBM MQ);
 * dozens of threads call {@link #structure(RawAddress)} simultaneously
 * on the same singleton bean.
 *
 * <h2>Calibration</h2>
 *
 * <p>Every implementation MUST be annotated {@link Calibrated} and MUST
 * have a corresponding {@link ConfidenceCalibrator} registered. Raw
 * confidence scores from different structurers are NOT comparable; the
 * calibrator normalises them so the merger can vote per field.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>{@link #name()} MUST be globally unique and stable across deploys.
 *       Used as the key for metrics, calibration, and audit.
 *   <li>{@link #supportedFields()} MUST be a subset of the fields this
 *       implementation can ever populate. The {@code FieldMerger} will
 *       discard any field returned by {@link #structure(RawAddress)} that
 *       is not in this set, even if the underlying sidecar produced it.
 *   <li>{@link #structure(RawAddress)} MUST be thread-safe and idempotent
 *       for identical inputs. It MUST NOT throw on bad input — return an
 *       empty result with diagnostics instead. It MAY throw on infrastructure
 *       failure (sidecar unreachable, timeout); Resilience4j wraps the call.
 * </ul>
 *
 * <p>This interface lives in the {@code domain} module and MUST NOT depend
 * on any adapter, Spring, or third-party library. The ArchUnit suite
 * enforces this; do not relax it.
 */
public interface AddressStructurer {

    /**
     * Stable, globally unique identifier for this structurer.
     * Lowercase, hyphen-separated. Examples: {@code "libpostal"},
     * {@code "swift-crf"}, {@code "llm"}, {@code "loqate"}.
     */
    String name();

    /**
     * The subset of {@link AddressField}s this structurer can populate.
     * Returned set is immutable and constant for the lifetime of the bean.
     */
    Set<AddressField> supportedFields();

    /**
     * Structure a single raw address.
     *
     * @param raw never null; may have empty content (handle gracefully)
     * @return never null; populated fields are a subset of
     *         {@link #supportedFields()}; missing fields are absent from the
     *         result map (do not include with empty {@link FieldValue})
     */
    StructuringResult structure(RawAddress raw);

    /**
     * Result of a single structurer call. Immutable.
     *
     * @param structurerName matches {@link AddressStructurer#name()} of the
     *                       producer; required for downstream calibration
     * @param fields         map keyed by {@link AddressField}; missing fields
     *                       are absent (not present with empty value)
     * @param latency        wall-clock duration of the call including network
     * @param diagnostics    free-form, never null, may be empty; used for
     *                       audit and debugging only — never for routing
     */
    record StructuringResult(
            String structurerName,
            Map<AddressField, FieldValue> fields,
            Duration latency,
            Map<String, Object> diagnostics) {
        public StructuringResult {
            Objects.requireNonNull(structurerName, "structurerName");
            Objects.requireNonNull(fields, "fields");
            Objects.requireNonNull(latency, "latency");
            Objects.requireNonNull(diagnostics, "diagnostics");
            fields = Map.copyOf(fields);
            diagnostics = Map.copyOf(diagnostics);
        }

        public static StructuringResult empty(String structurerName, Duration latency) {
            return new StructuringResult(structurerName, Map.of(), latency, Map.of());
        }
    }

    /**
     * A single field value produced by a structurer. Confidence is RAW — not
     * comparable across structurers until the {@link ConfidenceCalibrator}
     * normalises it.
     *
     * @param value      never null, may be empty (treat as missing)
     * @param confidence raw confidence as reported by the structurer; range
     *                   depends on the implementation (log-likelihood,
     *                   probability, etc.). Calibrator normalises to 0..1.
     */
    record FieldValue(String value, double confidence) {
        public FieldValue {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Closed enumeration of structurable address fields, mapped 1:1 onto
     * ISO 20022 PstlAdr elements. Order of declaration is the canonical
     * priority order for output (used by the merger when confidences tie).
     */
    enum AddressField {
        /** ISO 20022 {@code <Ctry>}. ISO 3166-1 alpha-2. Mandatory for SR2026. */
        CTRY,
        /** ISO 20022 {@code <TwnNm>}. Mandatory for SR2026. */
        TWN_NM,
        /** ISO 20022 {@code <PstCd>}. */
        PST_CD,
        /** ISO 20022 {@code <CtrySubDvsn>}. State, province, emirate. */
        CTRY_SUB_DVSN,
        /** ISO 20022 {@code <StrtNm>}. */
        STRT_NM,
        /** ISO 20022 {@code <BldgNb>}. */
        BLDG_NB,
        /** ISO 20022 {@code <BldgNm>}. */
        BLDG_NM,
        /** ISO 20022 {@code <AdrLine>}. Hybrid-mode fallback only. */
        ADR_LINE
    }
}

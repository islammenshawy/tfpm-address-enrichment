package com.jpmc.tfpm.address.domain;

import com.jpmc.tfpm.address.domain.AddressStructurer.AddressField;

/**
 * Per-structurer normaliser that converts raw confidence scores to a common
 * 0..1 calibrated probability of correctness.
 *
 * <p>Different structurers report confidence on different scales:
 * <ul>
 *   <li>libpostal returns log-likelihoods (typically -2.0 to 0.0)
 *   <li>CRFs return marginal probabilities (0.0 to 1.0)
 *   <li>LLMs return whatever the prompt asks for
 * </ul>
 *
 * <p>The {@code FieldMerger} compares calibrated values across structurers,
 * never raw values. Without calibration, libpostal's high-likelihood
 * "STRT_NM" might lose to swift-crf's lower-marginal-probability one,
 * even though libpostal is more correct.
 *
 * <h2>Implementation guidance</h2>
 *
 * <p>Day 1 implementations are identity calibrators (return raw passed
 * through) — fine because most structurers report something resembling a
 * probability already, and the merger still works correctly when ties
 * are broken by structurer declaration order.
 *
 * <p>Real calibration is learned from the accuracy harness's golden set
 * once enough samples are available. Typical implementations use isotonic
 * regression or Platt scaling per (structurer, field, country) tuple.
 *
 * <p>All implementations MUST be {@code @ThreadSafe} and stateless after
 * construction. Calibration tables loaded from configuration become
 * immutable {@code Map} fields on the bean.
 */
public interface ConfidenceCalibrator {

    /**
     * The structurer name this calibrator is bound to. Must match the
     * value of {@link AddressStructurer#name()} on the corresponding
     * structurer bean.
     */
    String structurerName();

    /**
     * Map a raw confidence value (as the structurer reported it) to a
     * calibrated probability of correctness in [0.0, 1.0].
     *
     * @param raw          the raw confidence as the structurer produced it
     * @param field        the field this confidence is for; calibration
     *                     can vary by field within a structurer
     * @param countryCode  ISO 3166-1 alpha-2 country code if known, "" otherwise;
     *                     calibration can vary by country (libpostal is more
     *                     accurate on US than UAE addresses, for example)
     * @return calibrated probability in [0.0, 1.0], NaN-safe; if the input
     *         is invalid, return 0.0 rather than NaN
     */
    double calibrate(double raw, AddressField field, String countryCode);
}

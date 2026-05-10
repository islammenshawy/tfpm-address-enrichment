package com.jpmc.tfpm.address.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation: this {@link AddressStructurer} implementation has
 * a corresponding {@link ConfidenceCalibrator} bean registered with the
 * same {@link AddressStructurer#name()} key.
 *
 * <p>Different structurers report confidence on different scales:
 * libpostal returns log-likelihoods, CRFs return marginal probabilities,
 * LLMs return whatever you prompt for. Comparing raw scores would yield
 * nonsense in the {@code FieldMerger}'s per-field voting.
 *
 * <p>The {@code ConfidenceCalibrator} for each structurer normalises raw
 * scores to a common 0..1 calibrated probability of correctness, learned
 * against the accuracy harness's golden set.
 *
 * <p>The {@code archunit-tests} module enforces that every implementer
 * of {@link AddressStructurer} carries this annotation, so a structurer
 * cannot accidentally bypass calibration and corrupt the merge.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Calibrated {}

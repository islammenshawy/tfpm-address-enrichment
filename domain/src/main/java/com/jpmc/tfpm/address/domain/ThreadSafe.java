package com.jpmc.tfpm.address.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation: this class is safe to use as a Spring singleton
 * shared across many concurrent threads.
 *
 * <p>The service runs as N replicas. Within each replica, multiple input
 * channels (HTTP, Kafka, RabbitMQ) deliver work concurrently to dozens of
 * worker threads. Every singleton on the request path must satisfy:
 *
 * <ol>
 *   <li>All instance fields are {@code final}.
 *   <li>All instance fields are one of:
 *       <ul>
 *         <li>a primitive,
 *         <li>an immutable value type ({@code String}, {@code Instant},
 *             {@code BigDecimal}, records, enums),
 *         <li>an immutable collection ({@code List.of(...)}, {@code Map.of(...)}),
 *         <li>a thread-safe collection ({@code ConcurrentHashMap}, etc.),
 *         <li>an atomic primitive ({@code AtomicLong}, {@code LongAdder}, etc.),
 *         <li>another {@code @ThreadSafe} bean.
 *       </ul>
 *   <li>No mutable static state.
 *   <li>No {@code ThreadLocal} for application data (OpenTelemetry
 *       handles trace context).
 *   <li>No {@code synchronized} blocks (use concurrent collections or
 *       atomics instead).
 * </ol>
 *
 * <p>The {@code archunit-tests} module enforces these rules at compile
 * time. A class annotated {@code @ThreadSafe} that violates any of them
 * fails the build.
 *
 * <p>If a class genuinely cannot be thread-safe (e.g. a JAXB
 * {@code Marshaller}), do not annotate it; create a fresh instance
 * per call instead, or pool through an explicitly thread-safe wrapper.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ThreadSafe {}

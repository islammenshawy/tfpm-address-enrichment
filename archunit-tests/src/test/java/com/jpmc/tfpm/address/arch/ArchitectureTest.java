package com.jpmc.tfpm.address.arch;

import com.jpmc.tfpm.address.domain.AddressEnrichmentService;
import com.jpmc.tfpm.address.domain.AddressStructurer;
import com.jpmc.tfpm.address.domain.Calibrated;
import com.jpmc.tfpm.address.domain.ConfidenceCalibrator;
import com.jpmc.tfpm.address.domain.IdempotencyStore;
import com.jpmc.tfpm.address.domain.LegacyAddressReader;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The architectural invariants of the system, enforced as build-time tests.
 *
 * <p>If a generated change would require relaxing one of these rules, the
 * change is wrong, not the rule. CLAUDE.md is the operating manual; this
 * class is its enforcement.
 *
 * <p>Run as part of {@code mvn verify}. CI gates on green here.
 */
@AnalyzeClasses(
        packages = "com.jpmc.tfpm.address",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class ArchitectureTest {

    // ============================================================
    // 1. Module-boundary rules
    // ============================================================

    @ArchTest
    static final ArchRule domain_has_no_outbound_dependencies =
            noClasses()
                    .that().resideInAPackage("com.jpmc.tfpm.address.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.jpmc.tfpm.address.adapter..",
                            "com.jpmc.tfpm.address.inbound..",
                            "com.jpmc.tfpm.address.app..",
                            "org.springframework..",
                            "jakarta.persistence..",
                            "org.jooq..",
                            "com.fasterxml..")
                    .because("domain must remain pure Java with no framework "
                            + "or third-party deps except SLF4J");

    @ArchTest
    static final ArchRule app_only_uses_domain_interfaces_for_collaboration =
            noClasses()
                    .that().resideInAPackage("com.jpmc.tfpm.address.app..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.jpmc.tfpm.address.adapter..")
                    .because("app composition uses domain interfaces only; "
                            + "concrete adapter classes are wired via Spring");

    @ArchTest
    static final ArchRule inbound_http_does_not_depend_on_kafka_or_mq =
            noClasses()
                    .that().resideInAPackage("com.jpmc.tfpm.address.inbound.http..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.jpmc.tfpm.address.inbound.kafka..",
                            "com.jpmc.tfpm.address.inbound.mq..")
                    .because("inbound channels are independent of each other");

    @ArchTest
    static final ArchRule inbound_does_not_depend_on_adapters_directly =
            noClasses()
                    .that().resideInAPackage("com.jpmc.tfpm.address.inbound..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.jpmc.tfpm.address.adapter..")
                    .because("inbound channels see domain interfaces only");

    // ============================================================
    // 2. Adapter encapsulation rules
    // ============================================================

    @ArchTest
    static final ArchRule jooq_types_only_referenced_inside_oracle_adapters =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "com.jpmc.tfpm.address.adapter.oracle..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.jooq..")
                    .because("jOOQ is an implementation detail of the oracle adapters");

    @ArchTest
    static final ArchRule prowide_types_only_referenced_inside_prowide_adapter =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "com.jpmc.tfpm.address.adapter.prowide..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.prowidesoftware..")
                    .because("Prowide is an implementation detail of the prowide adapter");

    @ArchTest
    static final ArchRule legacy_oracle_adapter_is_read_only =
            noClasses()
                    .that().resideInAPackage("com.jpmc.tfpm.address.adapter.oracle.legacy..")
                    .should().callMethodWhere(
                            DescribedPredicate.describe(
                                    "is a jOOQ DML operation",
                                    target -> {
                                        var name = target.getTarget().getName();
                                        return name.equals("insertInto")
                                                || name.equals("insertQuery")
                                                || name.equals("insert")
                                                || name.equals("update")
                                                || name.equals("delete")
                                                || name.equals("deleteFrom")
                                                || name.equals("deleteQuery")
                                                || name.equals("mergeInto")
                                                || name.equals("merge")
                                                || name.equals("truncate");
                                    }))
                    .because("legacy oracle schema must be read-only from this service "
                            + "(also enforced at db level via TFPM_LEGACY_RO grants)");

    // ============================================================
    // 3. Shadow-mode invariant
    // ============================================================

    @ArchTest
    static final ArchRule no_writes_to_payments_topics =
            noClasses()
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.jpmc.tfpm.address.app.config.PaymentsTopicWriter")
                    .because("the shadow-mode invariant forbids any code path that "
                            + "writes to topics matching ^payments\\..*. This is enforced "
                            + "at startup by KafkaTemplate factory validation as well.");

    // ============================================================
    // 4. Plugin contract: every structurer must be calibrated
    // ============================================================

    @ArchTest
    static final ArchRule every_structurer_is_calibrated =
            classes()
                    .that().implement(AddressStructurer.class)
                    .should().beAnnotatedWith(Calibrated.class)
                    .because("structurer confidence must always flow through "
                            + "a ConfidenceCalibrator before reaching the FieldMerger");

    @ArchTest
    static final ArchRule every_structurer_is_thread_safe =
            classes()
                    .that().implement(AddressStructurer.class)
                    .should().beAnnotatedWith(ThreadSafe.class)
                    .because("structurer beans are Spring singletons called from "
                            + "many concurrent threads (HTTP, Kafka, MQ)");

    // ============================================================
    // 5. Thread-safety contract on @ThreadSafe beans
    // ============================================================

    @ArchTest
    static final ArchRule thread_safe_beans_have_only_final_fields =
            fields()
                    .that().areDeclaredInClassesThat().areAnnotatedWith(ThreadSafe.class)
                    .and().areNotStatic()
                    .should().beFinal()
                    .because("any non-final instance field on a @ThreadSafe bean is "
                            + "a thread-safety bug waiting to happen");

    @ArchTest
    static final ArchRule thread_safe_bean_fields_are_immutable_or_concurrent =
            fields()
                    .that().areDeclaredInClassesThat().areAnnotatedWith(ThreadSafe.class)
                    .and().areNotStatic()
                    .should(beOfThreadSafeType())
                    .because("@ThreadSafe beans may only hold immutable values, "
                            + "thread-safe collections, atomics, or other @ThreadSafe beans");

    @ArchTest
    static final ArchRule key_collaborators_are_marked_thread_safe =
            classes()
                    .that().implement(AddressStructurer.class)
                    .or().implement(ConfidenceCalibrator.class)
                    .or().implement(AddressEnrichmentService.class)
                    .or().implement(IdempotencyStore.class)
                    .or().implement(LegacyAddressReader.class)
                    .should().beAnnotatedWith(ThreadSafe.class)
                    .because("every singleton on the request path must declare "
                            + "and prove its thread-safety contract");

    // ============================================================
    // 6. Forbidden patterns
    // ============================================================

    @ArchTest
    static final ArchRule no_field_injection =
            noClasses()
                    .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                    .because("use constructor injection only; @Autowired on fields "
                            + "breaks immutability and makes testing harder");

    @ArchTest
    static final ArchRule no_synchronized_methods =
            noClasses()
                    .that().resideInAPackage("com.jpmc.tfpm.address..")
                    .should(haveSynchronizedMethods())
                    .because("synchronized blocks are forbidden; use concurrent collections "
                            + "or atomics. If genuine mutual exclusion is required, use "
                            + "ReentrantLock with a comment explaining why concurrent "
                            + "alternatives don't work.");

    @ArchTest
    static final ArchRule no_thread_local_for_app_data =
            noClasses()
                    .that().resideInAPackage("com.jpmc.tfpm.address..")
                    .and().areNotAnnotatedWith("org.springframework.context.annotation.Configuration")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName(ThreadLocal.class.getName())
                    .because("ThreadLocal for application data is forbidden; "
                            + "OpenTelemetry handles trace context propagation");

    @ArchTest
    static final ArchRule no_mongodb_redis_or_other_stores =
            noClasses()
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.mongodb..",
                            "org.springframework.data.mongodb..",
                            "redis.clients..",
                            "io.lettuce..",
                            "co.elastic..",
                            "org.elasticsearch..",
                            "com.datastax..")
                    .because("persistence is Oracle-only by hard constraint; "
                            + "no Mongo, Redis, ElasticSearch, Cassandra");

    // ============================================================
    // Helper predicates
    // ============================================================

    private static com.tngtech.archunit.lang.ArchCondition<JavaField> beOfThreadSafeType() {
        Set<String> allowedTypes = Set.of(
                // Primitives
                "boolean", "byte", "char", "double", "float", "int", "long", "short",
                // Common immutables
                String.class.getName(),
                Instant.class.getName(),
                BigDecimal.class.getName(),
                java.time.Duration.class.getName(),
                java.time.LocalDate.class.getName(),
                java.time.LocalDateTime.class.getName(),
                java.time.OffsetDateTime.class.getName(),
                java.util.UUID.class.getName(),
                // Atomics
                AtomicReference.class.getName(),
                AtomicLong.class.getName(),
                AtomicInteger.class.getName(),
                AtomicBoolean.class.getName(),
                LongAdder.class.getName(),
                java.util.concurrent.atomic.LongAccumulator.class.getName(),
                // Concurrent collections
                ConcurrentMap.class.getName(),
                java.util.concurrent.ConcurrentHashMap.class.getName(),
                java.util.concurrent.ConcurrentSkipListMap.class.getName(),
                java.util.concurrent.CopyOnWriteArrayList.class.getName(),
                // Locks (allowed, but require comment)
                java.util.concurrent.locks.ReentrantLock.class.getName(),
                java.util.concurrent.locks.StampedLock.class.getName(),
                // Caffeine cache (preferred for size-bounded caches)
                "com.github.benmanes.caffeine.cache.Cache",
                "com.github.benmanes.caffeine.cache.LoadingCache",
                // Slf4j logger (immutable after construction)
                "org.slf4j.Logger",
                // Spring & framework singletons we trust
                "io.micrometer.core.instrument.MeterRegistry",
                "io.micrometer.core.instrument.Counter",
                "io.micrometer.core.instrument.Timer",
                "org.jooq.DSLContext",
                "org.springframework.web.reactive.function.client.WebClient",
                "org.springframework.kafka.core.KafkaTemplate",
                "org.springframework.jms.core.JmsTemplate",
                "io.grpc.ManagedChannel",
                "com.fasterxml.jackson.databind.ObjectMapper",
                "jakarta.xml.bind.JAXBContext");

        return new com.tngtech.archunit.lang.ArchCondition<JavaField>(
                "be of an immutable, atomic, or thread-safe type") {
            @Override
            public void check(JavaField field, com.tngtech.archunit.lang.ConditionEvents events) {
                String typeName = field.getRawType().getFullName();
                boolean ok = allowedTypes.contains(typeName)
                        // Records are immutable
                        || field.getRawType().getModifiers().contains(
                                com.tngtech.archunit.core.domain.JavaModifier.FINAL)
                                && field.getRawType().isRecord()
                        // Enums are immutable
                        || field.getRawType().isEnum()
                        // Other @ThreadSafe beans are allowed
                        || field.getRawType().isAnnotatedWith(ThreadSafe.class)
                        // Domain interfaces are allowed (impls must be @ThreadSafe themselves)
                        || typeName.startsWith("com.jpmc.tfpm.address.domain.")
                                && field.getRawType().getModifiers().contains(
                                        com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT);
                if (!ok) {
                    events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(
                            field,
                            String.format(
                                    "Field %s.%s of type %s is not in the allowed thread-safe set. "
                                            + "Either use a thread-safe type or do not annotate "
                                            + "the enclosing class @ThreadSafe.",
                                    field.getOwner().getName(),
                                    field.getName(),
                                    typeName)));
                }
            }
        };
    }

    private static com.tngtech.archunit.lang.ArchCondition<JavaClass> haveSynchronizedMethods() {
        return new com.tngtech.archunit.lang.ArchCondition<JavaClass>("have synchronized methods") {
            @Override
            public void check(JavaClass cls, com.tngtech.archunit.lang.ConditionEvents events) {
                cls.getMethods().forEach(m -> {
                    if (Modifier.isSynchronized(m.reflect().getModifiers())) {
                        events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(
                                m,
                                "Method " + cls.getName() + "." + m.getName()
                                        + " is synchronized; use concurrent collections or atomics instead"));
                    }
                });
            }
        };
    }
}

package com.jpmc.tfpm.address.adapter.oracle.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.tfpm.address.domain.*;

import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean registration for all adapter-oracle-app classes.
 * Accepts the {@code @Primary appWriteDsl} by type.
 */
@Configuration
public class OracleAppAdapterConfig {

    @Bean
    public IdempotencyStore oracleIdempotencyStore(DSLContext dsl) {
        return new OracleIdempotencyStore(dsl);
    }

    @Bean
    public ResultPersistence oracleResultPersistence(DSLContext dsl, ObjectMapper objectMapper) {
        return new OracleResultPersistence(dsl, objectMapper);
    }

    @Bean
    public ExceptionQueue jooqExceptionQueue(DSLContext dsl) {
        return new JooqExceptionQueue(dsl);
    }

    @Bean
    public AuditLog jooqAuditLog(DSLContext dsl) {
        return new JooqAuditLog(dsl);
    }

    @Bean
    public FieldAttributionWriter jooqFieldAttributionWriter(DSLContext dsl) {
        return new JooqFieldAttributionWriter(dsl);
    }

    @Bean
    public ComplianceRoutingWriter jooqComplianceRoutingWriter(DSLContext dsl) {
        return new JooqComplianceRoutingWriter(dsl);
    }

    @Bean
    public AccuracySampler jooqAccuracySampler(DSLContext dsl) {
        return new JooqAccuracySampler(dsl);
    }
}

package com.jpmc.tfpm.address.app.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Two HikariCP pools, two Oracle users, two jOOQ {@link DSLContext}s,
 * two transaction managers.
 *
 * <p>This separation is the core of the read/write isolation story:
 *
 * <ul>
 *   <li>The {@code legacy-read} pool authenticates as {@code TFPM_LEGACY_RO},
 *       which has SELECT-only grants on legacy tables. Even if a future
 *       bug attempts to write through this pool, the database refuses.
 *   <li>The {@code app-write} pool authenticates as {@code TFPM_ADDR_ENRICH_APP},
 *       which has DML grants on {@code TFPM_ADDR_ENRICH.*} only and no
 *       grants whatsoever on the legacy schema.
 * </ul>
 *
 * <p>Pool sizes assume 3-4 replicas. Coordinate with the DBA team on the
 * Oracle session limit before increasing — total connections from this
 * service is {@code N * (legacy-read max + app-write max)}.
 *
 * <p>The {@code app-write} {@link DSLContext} is marked {@code @Primary}
 * because most beans want the writable context; the {@code legacy-read}
 * variant is qualified by name where needed (in the
 * {@code adapter-oracle-legacy} module).
 */
@Configuration
public class DataSourceConfig {

    // ============================================================
    // Property holders
    // ============================================================

    @ConfigurationProperties(prefix = "spring.datasource.legacy-read")
    public static class LegacyReadProperties extends HikariConfig {}

    @ConfigurationProperties(prefix = "spring.datasource.app-write")
    public static class AppWriteProperties extends HikariConfig {}

    // ============================================================
    // Pools
    // ============================================================

    @Bean(name = "legacyReadDataSource")
    public DataSource legacyReadDataSource(LegacyReadProperties props) {
        return new LazyConnectionDataSourceProxy(new HikariDataSource(props));
    }

    @Primary
    @Bean(name = "appWriteDataSource")
    public DataSource appWriteDataSource(AppWriteProperties props) {
        return new LazyConnectionDataSourceProxy(new HikariDataSource(props));
    }

    // ============================================================
    // jOOQ contexts
    // ============================================================

    @Bean(name = "legacyReadDsl")
    public DSLContext legacyReadDsl(
            @org.springframework.beans.factory.annotation.Qualifier("legacyReadDataSource") DataSource ds,
            LegacyReadProperties props) {
        return buildDsl(ds, detectDialect(props.getJdbcUrl()));
    }

    @Primary
    @Bean(name = "appWriteDsl")
    public DSLContext appWriteDsl(
            @org.springframework.beans.factory.annotation.Qualifier("appWriteDataSource") DataSource ds,
            AppWriteProperties props) {
        return buildDsl(ds, detectDialect(props.getJdbcUrl()));
    }

    private static DSLContext buildDsl(DataSource ds, SQLDialect dialect) {
        var configuration = new DefaultConfiguration()
                .set(dialect)
                .set(new DataSourceConnectionProvider(ds));
        return new DefaultDSLContext(configuration);
    }

    private static SQLDialect detectDialect(String jdbcUrl) {
        if (jdbcUrl != null && jdbcUrl.startsWith("jdbc:h2:")) return SQLDialect.H2;
        return SQLDialect.DEFAULT;
    }

    // ============================================================
    // Transaction managers
    // ============================================================

    @Bean(name = "legacyReadTx")
    public PlatformTransactionManager legacyReadTx(
            @org.springframework.beans.factory.annotation.Qualifier("legacyReadDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Primary
    @Bean(name = "appWriteTx")
    public PlatformTransactionManager appWriteTx(
            @org.springframework.beans.factory.annotation.Qualifier("appWriteDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }
}

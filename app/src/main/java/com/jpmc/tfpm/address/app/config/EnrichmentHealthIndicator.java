package com.jpmc.tfpm.address.app.config;

import com.jpmc.tfpm.address.domain.ThreadSafe;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Custom health indicator that checks critical dependencies:
 * <ul>
 *   <li>Oracle connection pools (legacy-read and app-write)</li>
 *   <li>Pool saturation and idle connection counts</li>
 * </ul>
 *
 * <p>gRPC sidecar and LLM gateway health checks are deferred to
 * their respective adapter modules when those are @ConditionalOnProperty enabled.
 */
@Component
@ThreadSafe
public final class EnrichmentHealthIndicator implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(EnrichmentHealthIndicator.class);

    private final Map<String, DataSource> dataSources;

    public EnrichmentHealthIndicator(Map<String, DataSource> dataSources) {
        this.dataSources = dataSources;
    }

    @Override
    public Health health() {
        var builder = Health.up();

        for (var entry : dataSources.entrySet()) {
            var name = entry.getKey();
            var ds = entry.getValue();
            try {
                if (ds instanceof HikariDataSource hikari) {
                    var pool = hikari.getHikariPoolMXBean();
                    if (pool != null) {
                        builder.withDetail(name + ".active", pool.getActiveConnections());
                        builder.withDetail(name + ".idle", pool.getIdleConnections());
                        builder.withDetail(name + ".total", pool.getTotalConnections());
                        builder.withDetail(name + ".waiting", pool.getThreadsAwaitingConnection());

                        if (pool.getThreadsAwaitingConnection() > 10) {
                            builder.down().withDetail(name + ".status", "pool exhaustion risk");
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("Health check failed for {}: {}", name, e.getMessage());
                builder.down().withDetail(name + ".error", e.getMessage());
            }
        }

        return builder.build();
    }
}

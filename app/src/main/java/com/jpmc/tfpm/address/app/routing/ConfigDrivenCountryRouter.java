package com.jpmc.tfpm.address.app.routing;

import com.jpmc.tfpm.address.domain.CountryRouter;
import com.jpmc.tfpm.address.domain.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Config-driven country router that reads per-country structurer lists
 * from application.yml. Countries not configured use the full cascade.
 *
 * <p>Configuration example:
 * <pre>
 * enrichment.cascade.routing:
 *   CN: [llm]
 *   JP: [llm, swift-crf]
 * </pre>
 */
@ThreadSafe
public final class ConfigDrivenCountryRouter implements CountryRouter {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigDrivenCountryRouter.class);

    private final Map<String, List<String>> routingTable;

    public ConfigDrivenCountryRouter(Map<String, List<String>> routing) {
        Objects.requireNonNull(routing, "routing");
        var table = new ConcurrentHashMap<String, List<String>>();
        for (var entry : routing.entrySet()) {
            table.put(entry.getKey().toUpperCase(), List.copyOf(entry.getValue()));
        }
        this.routingTable = Collections.unmodifiableMap(table);
        LOG.info("Country router configured with {} country-specific routes: {}",
                routingTable.size(), routingTable.keySet());
    }

    @Override
    public List<String> structurersFor(String countryHint) {
        Objects.requireNonNull(countryHint, "countryHint");
        if (countryHint.isEmpty()) return List.of();
        return routingTable.getOrDefault(countryHint.toUpperCase(), List.of());
    }
}

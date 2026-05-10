package com.jpmc.tfpm.address.domain;

import java.util.List;
import java.util.Objects;

/**
 * Opt-in routing decision: for a given country hint, which structurers
 * should the cascade actually invoke?
 *
 * <p>libpostal accuracy varies by 25–40 percentage points across countries.
 * For some country/structurer combinations (e.g. libpostal on CN, where
 * its left-to-right parsing produces noise rather than signal), running
 * the structurer at all is counterproductive.
 *
 * <p>This interface lets the cascade orchestrator filter its
 * {@code List<AddressStructurer>} per-request based on the country hint,
 * without hardcoding country/structurer knowledge into the orchestrator.
 *
 * <p>The default implementation in {@code app} returns an empty list for
 * every country (use the full cascade). Production config replaces it
 * with a configuration-driven implementation that reads
 * {@code enrichment.cascade.routing.<COUNTRY>} from {@code application.yml}.
 *
 * <p>See {@code docs/COUNTRY_STRATEGY.md} section 4 for the per-country
 * routing strategy, including which structurers are known-weak on which
 * countries and how to evolve the routing table from the accuracy
 * harness output.
 *
 * <p>Implementations MUST be {@link ThreadSafe}.
 */
public interface CountryRouter {

    /**
     * Which structurer names should the cascade invoke for an address with
     * the given country hint?
     *
     * @param countryHint ISO 3166-1 alpha-2; empty string means "no hint"
     * @return ordered list of {@link AddressStructurer#name()} values to
     *         invoke; an empty list means "use the full cascade as
     *         declared in {@code enrichment.cascade.order}". Returning
     *         a list with names not present in the cascade is a no-op
     *         for those names; the orchestrator does not error.
     */
    List<String> structurersFor(String countryHint);

    /**
     * Default no-op router: never filters. Useful for tests and as the
     * Day 1 default before any country-specific routing is configured.
     */
    static CountryRouter noOp() {
        return new CountryRouter() {
            @Override public List<String> structurersFor(String countryHint) {
                Objects.requireNonNull(countryHint, "countryHint");
                return List.of();
            }
        };
    }
}

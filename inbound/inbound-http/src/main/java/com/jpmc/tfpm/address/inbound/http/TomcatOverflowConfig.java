package com.jpmc.tfpm.address.inbound.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Returns 503 with Retry-After header when the server is under heavy load.
 * Tracks active requests and rejects new ones when a configurable limit is hit.
 */
@Configuration
public class TomcatOverflowConfig {

    @Value("${server.overflow.max-concurrent-requests:400}")
    private int maxConcurrentRequests;

    @Value("${server.overflow.retry-after-seconds:5}")
    private int retryAfterSeconds;

    @Bean
    public OncePerRequestFilter overflowProtectionFilter() {
        var activeRequests = new AtomicInteger(0);
        final int maxRequests = this.maxConcurrentRequests;
        final int retryAfter = this.retryAfterSeconds;

        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                if (activeRequests.incrementAndGet() > maxRequests) {
                    activeRequests.decrementAndGet();
                    response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                    response.setHeader("Retry-After", String.valueOf(retryAfter));
                    response.setContentType("application/problem+json");
                    response.getWriter().write(
                            "{\"type\":\"urn:tfpm:address:error:overloaded\"," +
                            "\"title\":\"Service Unavailable\"," +
                            "\"status\":503," +
                            "\"detail\":\"Server is at capacity. Please retry after " +
                            retryAfter + " seconds.\"}");
                    return;
                }
                try {
                    filterChain.doFilter(request, response);
                } finally {
                    activeRequests.decrementAndGet();
                }
            }
        };
    }
}

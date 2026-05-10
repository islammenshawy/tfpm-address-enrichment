package com.jpmc.tfpm.address.inbound.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Sets the MDC traceId from the X-Correlation-Id request header
 * so all log statements within the request context include it.
 * Clears MDC after the request completes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcCorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("traceId", correlationId);
        try {
            response.setHeader("X-Correlation-Id", correlationId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}

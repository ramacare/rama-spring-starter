package org.rama.cors;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * Blanket CORS filter consolidated from copy-pasted variants in `ramaservice` and
 * `his-service` (each repo carried their own `CORSFilter.java`). Sets the
 * `Access-Control-Allow-*` headers from constructor parameters and short-circuits
 * `OPTIONS` preflight requests with `200 OK` so the actual request can proceed.
 *
 * <p>This is the imperative-filter style that bypasses Spring's
 * {@link org.springframework.web.cors.CorsConfigurationSource}-based handling.
 * Consumers using Spring's idiomatic CORS abstraction should leave
 * {@code rama.cors.enabled=false} (the default) and use the per-source
 * {@link IdempotencyAwareCorsConfigurationSource} contribution instead.</p>
 *
 * <p>Registered as a {@code FilterRegistrationBean} at
 * {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE} so the headers are
 * applied before any auth filter rejects the preflight.</p>
 */
@Slf4j
public class RamaCorsFilter implements Filter {

    private final String allowedOrigins;
    private final String allowedMethods;
    private final String allowedHeaders;

    public RamaCorsFilter(String allowedOrigins, String allowedMethods, String allowedHeaders) {
        this.allowedOrigins = allowedOrigins;
        this.allowedMethods = allowedMethods;
        this.allowedHeaders = allowedHeaders;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        response.setHeader("Access-Control-Allow-Origin", allowedOrigins);
        response.setHeader("Access-Control-Allow-Methods", allowedMethods);
        response.setHeader("Access-Control-Allow-Headers", allowedHeaders);
        // Short-circuit the preflight before the auth filters get a chance to
        // reject it (ramaservice's variant — his-service's missing this caused
        // intermittent 401s on preflight).
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        chain.doFilter(req, res);
    }
}

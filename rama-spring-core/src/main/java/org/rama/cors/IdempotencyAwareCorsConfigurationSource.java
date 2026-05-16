package org.rama.cors;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Decorator over an existing {@link CorsConfigurationSource} that appends the
 * idempotency header (default {@code Idempotency-Key}, overridable via
 * {@code rama.idempotency.header-name}) to the delegate's
 * {@link CorsConfiguration#getAllowedHeaders()} list, so browser preflight
 * checks allow the header without the consumer having to maintain the list
 * themselves.
 *
 * <p>The decorator is strictly additive:
 * <ul>
 *   <li>If the delegate returns {@code null} (no CORS for this request) we
 *       return {@code null} unchanged.</li>
 *   <li>If {@code allowedHeaders} is {@code null} (Spring treats this as
 *       "allow all") we leave it alone — adding our header would actually
 *       narrow the policy.</li>
 *   <li>If {@code allowedHeaders} contains {@code "*"} (wildcard) we leave
 *       it alone for the same reason.</li>
 *   <li>If our header is already present we return the delegate's
 *       configuration unchanged.</li>
 *   <li>Otherwise we return a per-request copy of the delegate's
 *       configuration with the header appended — never mutating the
 *       delegate's underlying {@link CorsConfiguration} instance.</li>
 * </ul>
 *
 * <p>Returning a copy is important because Spring caches the delegate's
 * {@link CorsConfiguration} instances and re-serves them across requests;
 * mutating one would compound the header on every subsequent call.
 */
public class IdempotencyAwareCorsConfigurationSource implements CorsConfigurationSource {

    private static final String WILDCARD = "*";

    private final CorsConfigurationSource delegate;
    private final String headerName;

    public IdempotencyAwareCorsConfigurationSource(CorsConfigurationSource delegate, String headerName) {
        if (delegate == null) throw new IllegalArgumentException("delegate is required");
        if (headerName == null || headerName.isBlank()) throw new IllegalArgumentException("headerName is required");
        this.delegate = delegate;
        this.headerName = headerName;
    }

    public CorsConfigurationSource getDelegate() {
        return delegate;
    }

    public String getHeaderName() {
        return headerName;
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        CorsConfiguration original = delegate.getCorsConfiguration(request);
        if (original == null) {
            return null;
        }
        List<String> allowed = original.getAllowedHeaders();
        if (allowed == null) {
            // null means "no restriction" — adding our header would actually narrow it.
            return original;
        }
        if (allowed.contains(WILDCARD)) {
            return original;
        }
        if (IdempotencyHeaderSupport.containsIgnoreCase(allowed, headerName)) {
            return original;
        }
        // Spring's CorsConfiguration copy constructor shares the underlying
        // list reference for allowedHeaders. Replace it with a fresh ArrayList
        // before appending so we never mutate the delegate's list — otherwise
        // the header would compound across requests as Spring caches the
        // delegate's CorsConfiguration instance.
        CorsConfiguration copy = new CorsConfiguration(original);
        List<String> copiedHeaders = new ArrayList<>(allowed);
        copiedHeaders.add(headerName);
        copy.setAllowedHeaders(copiedHeaders);
        return copy;
    }
}

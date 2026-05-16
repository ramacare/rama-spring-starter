package org.rama.cors;

import org.rama.service.idempotency.IdempotencyProperties;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

/**
 * Shared helpers for resolving the configured idempotency header name and
 * checking whether a header list already contains it. Used by both
 * {@code RamaCorsAutoConfiguration} (when building the blanket filter's
 * Allow-Headers list) and {@link IdempotencyAwareCorsConfigurationSource}
 * (when deciding whether to augment a Spring-managed {@code CorsConfiguration}).
 *
 * <p>Kept package-friendly as a static utility rather than a Spring bean: the
 * autoconfigs need to call these from places where injecting a dedicated bean
 * (a {@code BeanPostProcessor}, an {@code @Bean} method that returns a
 * {@code FilterRegistrationBean}) would be awkward.</p>
 */
public final class IdempotencyHeaderSupport {

    /**
     * Fallback when {@link IdempotencyProperties} isn't yet available or has a
     * blank {@code headerName}. Matches the frontend's
     * {@code @ramathibodi/nuxt-commons} default and graphql-java's spec for
     * the header name.
     */
    public static final String DEFAULT_HEADER_NAME = "Idempotency-Key";

    private IdempotencyHeaderSupport() {
    }

    /**
     * Resolve the configured header name, falling back to {@link #DEFAULT_HEADER_NAME}
     * when the {@code IdempotencyProperties} bean is missing (e.g. because the
     * bean post-processor that calls this fires before the
     * {@code @ConfigurationProperties} bean is bound) or carries a blank name.
     */
    public static String resolveHeaderName(ObjectProvider<IdempotencyProperties> propertiesProvider) {
        IdempotencyProperties properties = propertiesProvider.getIfAvailable();
        if (properties == null) return DEFAULT_HEADER_NAME;
        String configured = properties.getHeaderName();
        if (configured == null || configured.isBlank()) return DEFAULT_HEADER_NAME;
        return configured;
    }

    /** Case-insensitive {@link String#equalsIgnoreCase} membership check. */
    public static boolean containsIgnoreCase(List<String> list, String value) {
        if (list == null || value == null) return false;
        for (String entry : list) {
            if (entry != null && entry.equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}

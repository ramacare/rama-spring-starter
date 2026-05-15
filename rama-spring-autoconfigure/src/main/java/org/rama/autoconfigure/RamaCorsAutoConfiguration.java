package org.rama.autoconfigure;

import org.rama.cors.RamaCorsFilter;
import org.rama.cors.RamaCorsProperties;
import org.rama.service.idempotency.IdempotencyProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires the bundled {@link RamaCorsFilter} (issue #26) — the consolidated
 * replacement for the duplicate {@code CORSFilter}s that lived in
 * `ramaservice/.../config/CORSFilter.java` and
 * `his-service/.../config/CORSFilter.java`.
 *
 * <p>Default {@code rama.cors.enabled=true} — both source consumers already
 * ran with this filter active so the bundled version preserves their behaviour
 * on upgrade. Set {@code rama.cors.enabled=false} for apps using Spring's
 * idiomatic {@code CorsConfigurationSource} handling. The filter is registered
 * at {@link Ordered#HIGHEST_PRECEDENCE} so the CORS headers (and the
 * {@code OPTIONS} short-circuit) win over any auth filters.</p>
 *
 * <p>The idempotency header from {@link IdempotencyProperties} (default
 * {@code Idempotency-Key}) is auto-appended to {@code allowedHeaders} unless the
 * consumer's configured list already includes it — keeps {@code rama.cors} and
 * {@code rama.idempotency} in sync without making the consumer maintain both.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "rama.cors", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({RamaCorsProperties.class, IdempotencyProperties.class})
public class RamaCorsAutoConfiguration {

    @Bean
    FilterRegistrationBean<RamaCorsFilter> ramaCorsFilterRegistration(
            RamaCorsProperties corsProperties,
            ObjectProvider<IdempotencyProperties> idempotencyPropertiesProvider) {
        String allowedHeaders = buildAllowedHeaders(corsProperties, idempotencyPropertiesProvider);
        RamaCorsFilter filter = new RamaCorsFilter(
                corsProperties.getAllowedOrigins(),
                corsProperties.getAllowedMethods(),
                allowedHeaders);
        FilterRegistrationBean<RamaCorsFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    private static String buildAllowedHeaders(
            RamaCorsProperties corsProperties,
            ObjectProvider<IdempotencyProperties> idempotencyPropertiesProvider) {
        List<String> headers = new ArrayList<>(corsProperties.getAllowedHeaders());
        String idempotencyHeader = resolveIdempotencyHeader(idempotencyPropertiesProvider);
        if (idempotencyHeader != null && !containsIgnoreCase(headers, idempotencyHeader)) {
            headers.add(idempotencyHeader);
        }
        return String.join(", ", headers);
    }

    private static String resolveIdempotencyHeader(ObjectProvider<IdempotencyProperties> provider) {
        IdempotencyProperties properties = provider.getIfAvailable();
        if (properties == null) return "Idempotency-Key";
        String configured = properties.getHeaderName();
        if (configured == null || configured.isBlank()) return "Idempotency-Key";
        return configured;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String entry : list) {
            if (entry != null && entry.equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}

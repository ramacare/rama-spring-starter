package org.rama.autoconfigure;

import org.rama.cors.IdempotencyAwareCorsConfigurationSource;
import org.rama.service.idempotency.IdempotencyProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Wraps any {@link CorsConfigurationSource} bean the consumer registers so that
 * the idempotency header (default {@code Idempotency-Key}, overridable via
 * {@code rama.idempotency.header-name}) is automatically present in every
 * preflight response's {@code Access-Control-Allow-Headers} list — without
 * touching the consumer's CORS policy in any other way.
 *
 * <p>Motivated by starter issue #25: the matching frontend module
 * ({@code @ramathibodi/nuxt-commons}) emits {@code Idempotency-Key} on every
 * authenticated POST after rama-modules#232. Consumers that ship a strict
 * {@code allowedHeaders} list (Spring MVC's {@code mvcCorsConfigurationSource}
 * or a Spring Security {@code CorsConfigurationSource} bean) were getting
 * preflight failures until they remembered to add the header themselves —
 * a paper cut the starter is better positioned to handle.
 *
 * <p>Opt-out: set {@code rama.idempotency.cors.augment=false} to disable.
 *
 * <p>Behavior is implemented by {@link IdempotencyAwareCorsConfigurationSource};
 * see its Javadoc for the exact additive semantics (wildcard / null
 * allowedHeaders are left alone, the underlying config is never mutated).
 */
@AutoConfiguration(after = RamaStarterAutoConfiguration.class)
@ConditionalOnClass(CorsConfigurationSource.class)
@ConditionalOnProperty(prefix = "rama.idempotency.cors", name = "augment", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class RamaIdempotencyCorsAutoConfiguration {

    /**
     * Returned <strong>static</strong> per Spring's BeanPostProcessor contract:
     * a non-static {@code @Bean} BPP would be instantiated too early and prevent
     * normal {@code @ConfigurationProperties} binding for any bean created before
     * it. The {@link ObjectProvider} of {@link IdempotencyProperties} defers the
     * actual lookup until each {@link CorsConfigurationSource} is wrapped — at
     * which point property binding has completed.
     */
    @Bean
    static BeanPostProcessor ramaIdempotencyCorsBeanPostProcessor(ObjectProvider<IdempotencyProperties> propertiesProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof CorsConfigurationSource source)) {
                    return bean;
                }
                if (bean instanceof IdempotencyAwareCorsConfigurationSource) {
                    // Already wrapped (e.g. consumer registered our class directly).
                    return bean;
                }
                String headerName = resolveHeaderName(propertiesProvider);
                if (headerName == null) {
                    return bean;
                }
                return new IdempotencyAwareCorsConfigurationSource(source, headerName);
            }
        };
    }

    private static String resolveHeaderName(ObjectProvider<IdempotencyProperties> propertiesProvider) {
        IdempotencyProperties properties = propertiesProvider.getIfAvailable();
        if (properties == null) {
            return "Idempotency-Key";
        }
        String configured = properties.getHeaderName();
        if (configured == null || configured.isBlank()) {
            return "Idempotency-Key";
        }
        return configured;
    }
}

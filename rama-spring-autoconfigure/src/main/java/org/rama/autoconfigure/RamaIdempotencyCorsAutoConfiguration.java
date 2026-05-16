package org.rama.autoconfigure;

import org.rama.cors.IdempotencyAwareCorsConfigurationSource;
import org.rama.cors.IdempotencyHeaderSupport;
import org.rama.service.idempotency.IdempotencyProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Wraps any {@link CorsConfigurationSource} bean the consumer registers so the
 * idempotency header (default {@code Idempotency-Key}, overridable via
 * {@code rama.idempotency.header-name}) lands in every preflight response's
 * {@code Access-Control-Allow-Headers} — without otherwise touching the
 * consumer's CORS policy.
 *
 * <p>Motivated by starter issue #25: the matching frontend module
 * ({@code @ramathibodi/nuxt-commons}) emits {@code Idempotency-Key} on every
 * authenticated POST after rama-modules#232. Consumers shipping a strict
 * {@code allowedHeaders} list (Spring MVC's {@code mvcCorsConfigurationSource}
 * or a Spring Security {@code CorsConfigurationSource}) hit preflight failures
 * until they remembered to add the header themselves — a paper cut the starter
 * is better positioned to handle.</p>
 *
 * <p><strong>Activation</strong> is mutually exclusive with the blanket
 * {@code RamaCorsFilter}: this auto-config only registers when
 * {@code rama.cors.enabled=false} AND
 * {@code rama.idempotency.cors.augment=true} (the latter default-on). The
 * blanket filter, when active, sets the response headers itself at
 * {@code HIGHEST_PRECEDENCE} and short-circuits OPTIONS preflights before
 * Spring's CORS processor ever consults a {@code CorsConfigurationSource},
 * so wrapping those sources would be invisible work — see issue #25.</p>
 *
 * <p>Opt-out (when the filter is off): set
 * {@code rama.idempotency.cors.augment=false}.</p>
 *
 * <p>Behavior is implemented by {@link IdempotencyAwareCorsConfigurationSource};
 * see its Javadoc for the exact additive semantics (wildcard / null
 * {@code allowedHeaders} are left alone, the underlying config is never
 * mutated).</p>
 */
@AutoConfiguration(after = RamaStarterAutoConfiguration.class)
@ConditionalOnClass(CorsConfigurationSource.class)
@Conditional(RamaIdempotencyCorsAutoConfiguration.OnAugmenterEligible.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class RamaIdempotencyCorsAutoConfiguration {

    /**
     * Returned <strong>static</strong> per Spring's BeanPostProcessor contract:
     * a non-static {@code @Bean} BPP would be instantiated too early and
     * prevent normal {@code @ConfigurationProperties} binding for any bean
     * created before it. The {@link ObjectProvider} defers
     * {@link IdempotencyProperties} lookup until each
     * {@link CorsConfigurationSource} is wrapped — by then property binding
     * has completed.
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
                String headerName = IdempotencyHeaderSupport.resolveHeaderName(propertiesProvider);
                return new IdempotencyAwareCorsConfigurationSource(source, headerName);
            }
        };
    }

    /**
     * The augmenter only kicks in when the blanket {@link RamaCorsFilter} is
     * disabled AND the augmenter itself hasn't been explicitly opted out. Both
     * properties carry their own {@code matchIfMissing} semantics:
     * {@code rama.cors.enabled} defaults to {@code true} (filter on, so we
     * default to OFF here); {@code rama.idempotency.cors.augment} defaults to
     * {@code true} so the augmenter activates automatically the moment a
     * consumer flips the filter off.
     */
    static final class OnAugmenterEligible extends AllNestedConditions {
        OnAugmenterEligible() {
            super(ConfigurationCondition.ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnProperty(prefix = "rama.cors", name = "enabled", havingValue = "false")
        static class BlanketFilterDisabled {
        }

        @ConditionalOnProperty(prefix = "rama.idempotency.cors", name = "augment", havingValue = "true", matchIfMissing = true)
        static class AugmenterEnabled {
        }
    }
}

package org.rama.service.idempotency;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "rama.idempotency")
public class IdempotencyProperties {

    private boolean enabled = true;

    private Duration defaultTtl = Duration.ofSeconds(30);

    private String headerName = "Idempotency-Key";

    private Duration cleanupInterval = Duration.ofMinutes(5);

    /**
     * Advisor order of {@code IdempotencyAspect}. The default keeps it inside Spring
     * Security's method interceptors (orders 100–600, so a replay is still authorized)
     * and outside everything left at the default {@code LOWEST_PRECEDENCE} —
     * {@code @Transactional} and consumer aspects. A side-effect aspect on an
     * {@code @IdempotentMutation} method therefore fires once, not again on every
     * replay. See starter#64.
     */
    private int aspectOrder = 1000;
}

package org.rama.service.idempotency;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@ConfigurationProperties(prefix = "rama.idempotency")
public class IdempotencyProperties {

    private boolean enabled = true;

    /**
     * How long a call that carried the idempotency header is replayed, for any
     * {@code @IdempotentMutation} that sets no {@code ttl} of its own.
     */
    private Duration defaultTtl = Duration.ofSeconds(30);

    /**
     * How long a call that carried <em>no</em> idempotency header is replayed, counted from
     * when it finished, for any {@code @IdempotentMutation} that sets no
     * {@code fallbackWindow} of its own. Such a call is matched by method, user and
     * arguments, so this also swallows a deliberate identical repeat inside the window —
     * keep it short. {@code 0s} turns header-less deduplication off. See starter#67.
     */
    private Duration fallbackWindow = Duration.ofSeconds(5);

    /**
     * Extra property / map-key names left out of a header-less call's signature, at any
     * depth, on top of {@code requestDatetime} and {@code traceId}. For per-request values a
     * client puts in the payload (timestamps, correlation ids) that would otherwise make
     * every duplicate look new. See starter#67.
     */
    private Set<String> volatileFields = new LinkedHashSet<>();

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

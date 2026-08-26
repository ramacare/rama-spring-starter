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
}

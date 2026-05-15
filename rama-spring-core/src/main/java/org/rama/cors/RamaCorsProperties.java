package org.rama.cors;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration for the bundled {@link RamaCorsFilter}.
 *
 * <p>Defaults mirror the de-facto values both `ramaservice` and `his-service`
 * carried in their local copies before this filter moved into the starter
 * (issue #26). Default {@link #enabled} is {@code false} so consumers must
 * explicitly opt in — the strict-secure default keeps existing apps and apps
 * using Spring's idiomatic {@code CorsConfigurationSource} unaffected.</p>
 */
@Data
@ConfigurationProperties(prefix = "rama.cors")
public class RamaCorsProperties {

    private boolean enabled = false;

    private String allowedOrigins = "*";

    private String allowedMethods = "POST, GET, OPTIONS, PUT, DELETE";

    /**
     * Headers permitted via {@code Access-Control-Allow-Headers}. Joined with
     * {@code ", "} when written to the response. {@code Idempotency-Key} is
     * auto-merged in the autoconfig from {@code IdempotencyProperties.headerName}
     * so consumers don't have to keep the lists in sync; if the consumer overrides
     * this list, the idempotency header is still appended unless they already
     * include it explicitly.
     */
    private List<String> allowedHeaders = new ArrayList<>(Arrays.asList(
            "Accept",
            "Content-Type",
            "Origin",
            "X-Requested-With",
            "Last-Modified",
            "Authorization",
            "Referrer-Policy"
    ));
}

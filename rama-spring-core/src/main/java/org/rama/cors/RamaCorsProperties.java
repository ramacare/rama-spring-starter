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
 * (issue #26), including a default-on {@link #enabled} flag: those consumers
 * already ran with this exact filter active, and bundling without flipping the
 * default would require every consumer to add a property during migration.
 * Set {@code rama.cors.enabled=false} to disable — required for apps using
 * Spring's idiomatic {@code CorsConfigurationSource}-based CORS handling, since
 * the bundled filter sets the response headers at HIGHEST_PRECEDENCE and would
 * override anything Spring computes later in the chain.</p>
 */
@Data
@ConfigurationProperties(prefix = "rama.cors")
public class RamaCorsProperties {

    private boolean enabled = true;

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

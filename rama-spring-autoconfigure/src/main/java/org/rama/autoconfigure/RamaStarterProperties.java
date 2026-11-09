package org.rama.autoconfigure;

import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@ConfigurationProperties(prefix = "rama")
public class RamaStarterProperties {
    private final Jpa jpa = new Jpa();
    private final StaticValues staticValues = new StaticValues();
    private final Revision revision = new Revision();
    private final Mongo mongo = new Mongo();
    private final Meilisearch meilisearch = new Meilisearch();
    private final Graphql graphql = new Graphql();
    private final Quartz quartz = new Quartz();
    private final Ftp ftp = new Ftp();
    private final ApiKey apiKey = new ApiKey();
    private final Api api = new Api();

    @Data
    public static class Jpa {
        private boolean enabled = true;
    }

    @Data
    public static class StaticValues {
        private boolean enabled = true;
        private String groupKey = "$StaticValue";
        private String currentUsernameFallbackKey;
        private Duration refreshTtl = Duration.ofMinutes(5);
    }

    @Data
    public static class Revision {
        private boolean enabled = true;
    }

    @Data
    public static class Mongo {
        private boolean enabled = true;
        private boolean deferredIndexesEnabled = true;
        /** Times a field-set must be queried -- cumulatively, not per window -- before its index is created. */
        private int deferredIndexThreshold = 100;
        /** How often accumulated usage is examined and qualifying indexes are created. */
        private Duration deferredIndexFlushInterval = Duration.ofMinutes(10);
    }

    @Data
    public static class Meilisearch {
        private boolean enabled = true;
        private boolean initializeIndexes = true;
    }

    @Data
    public static class Graphql {
        private boolean enabled = true;
    }

    @Data
    public static class Quartz {
        private List<String> allowedJobPackages = new ArrayList<>();
    }

    @Data
    public static class Ftp {
        private boolean enabled = false;
    }

    @Data
    public static class ApiKey {
        private boolean enabled = false;
    }

    /**
     * Outbound HTTP settings for {@link org.rama.service.GenericApiService}. These stop
     * {@code callApi} waiting on an unreachable or silent upstream, which during an incident
     * holds DB connections and contributes to Hikari pool exhaustion (see starter#66).
     *
     * <p>They are not a total deadline for the call: an upstream that keeps trickling data can
     * run past {@code responseTimeout}, and the wait for a pooled connection is limited
     * separately by Reactor Netty's pending-acquire timeout (45s by default).
     */
    @Data
    public static class Api {
        /** Maximum time to establish the TCP connection to an upstream API. */
        private Duration connectTimeout = Duration.ofSeconds(3);
        /** Maximum interval between network reads of the upstream response; not a limit on the whole call. */
        private Duration responseTimeout = Duration.ofSeconds(15);
    }
}

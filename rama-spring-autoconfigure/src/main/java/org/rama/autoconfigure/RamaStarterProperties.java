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
}

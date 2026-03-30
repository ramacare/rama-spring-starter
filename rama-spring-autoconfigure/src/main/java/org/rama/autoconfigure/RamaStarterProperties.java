package org.rama.autoconfigure;

import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@ConfigurationProperties(prefix = "rama")
public class RamaStarterProperties {
    private final Jpa jpa = new Jpa();
    private final Storage storage = new Storage();
    private final Document document = new Document();
    private final StaticValues staticValues = new StaticValues();
    private final Revision revision = new Revision();
    private final Mongo mongo = new Mongo();
    private final Meilisearch meilisearch = new Meilisearch();
    private final Graphql graphql = new Graphql();
    private final Encryption encryption = new Encryption();

    @Data
    public static class Storage {
        private String fileStoragePath = "./data";
        private String fileStorageLocation = "s3";
        private String minioEndpoint;
        private String minioAccessKey;
        private String minioSecretKey;
    }

    @Data
    public static class Jpa {
        private boolean enabled = true;
    }

    @Data
    public static class Document {
        private String gotenbergServer = "http://localhost:3000";
        private String placeholderPattern = "\\{\\{(.+?)\\}\\}";
        private String sectionStartPattern = "\\{\\{[^\\{\\}]*startsec[^\\{\\}]*\\}\\}";
        private String sectionEndPattern = "\\{\\{[\\s]*placeholder[^\\{\\}]*endsec[^\\{\\}]*\\}\\}";
        private String sectionItemPattern = "\\{\\{[\\s]*placeholder[^\\{\\}]*\\}\\}";
        private String repeatAttributeProperty = "RepeatAttribute";
        private String maximumPagesProperty = "MaximumPages";
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
        private String hostUrl;
        private String apiKey;
    }

    @Data
    public static class Graphql {
        private boolean enabled = true;
    }

    @Data
    public static class Encryption {
        private String key;
    }
}

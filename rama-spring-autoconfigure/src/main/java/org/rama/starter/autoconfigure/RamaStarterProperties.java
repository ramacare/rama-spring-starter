package org.rama.starter.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "rama.starter")
public class RamaStarterProperties {
    private final Storage storage = new Storage();
    private final Document document = new Document();
    private final StaticValues staticValues = new StaticValues();
    private final Revision revision = new Revision();
    private final Mongo mongo = new Mongo();
    private final Meilisearch meilisearch = new Meilisearch();
    private final Graphql graphql = new Graphql();

    public Storage getStorage() {
        return storage;
    }

    public Document getDocument() {
        return document;
    }

    public StaticValues getStaticValues() {
        return staticValues;
    }

    public Revision getRevision() {
        return revision;
    }

    public Mongo getMongo() {
        return mongo;
    }

    public Meilisearch getMeilisearch() {
        return meilisearch;
    }

    public Graphql getGraphql() {
        return graphql;
    }

    public static class Storage {
        private String fileStoragePath = "./data";
        private String fileStorageLocation = "s3";
        private String minioEndpoint;
        private String minioAccessKey;
        private String minioSecretKey;

        public String getFileStoragePath() {
            return fileStoragePath;
        }

        public void setFileStoragePath(String fileStoragePath) {
            this.fileStoragePath = fileStoragePath;
        }

        public String getFileStorageLocation() {
            return fileStorageLocation;
        }

        public void setFileStorageLocation(String fileStorageLocation) {
            this.fileStorageLocation = fileStorageLocation;
        }

        public String getMinioEndpoint() {
            return minioEndpoint;
        }

        public void setMinioEndpoint(String minioEndpoint) {
            this.minioEndpoint = minioEndpoint;
        }

        public String getMinioAccessKey() {
            return minioAccessKey;
        }

        public void setMinioAccessKey(String minioAccessKey) {
            this.minioAccessKey = minioAccessKey;
        }

        public String getMinioSecretKey() {
            return minioSecretKey;
        }

        public void setMinioSecretKey(String minioSecretKey) {
            this.minioSecretKey = minioSecretKey;
        }
    }

    public static class Document {
        private String gotenbergServer = "http://localhost:3000";
        private String placeholderPattern = "\\{\\{(.+?)\\}\\}";

        public String getGotenbergServer() {
            return gotenbergServer;
        }

        public void setGotenbergServer(String gotenbergServer) {
            this.gotenbergServer = gotenbergServer;
        }

        public String getPlaceholderPattern() {
            return placeholderPattern;
        }

        public void setPlaceholderPattern(String placeholderPattern) {
            this.placeholderPattern = placeholderPattern;
        }
    }

    public static class StaticValues {
        private boolean enabled = true;
        private String groupKey = "$StaticValue";
        private String currentUsernameFallbackKey;
        private Duration refreshTtl = Duration.ofMinutes(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getGroupKey() {
            return groupKey;
        }

        public void setGroupKey(String groupKey) {
            this.groupKey = groupKey;
        }

        public String getCurrentUsernameFallbackKey() {
            return currentUsernameFallbackKey;
        }

        public void setCurrentUsernameFallbackKey(String currentUsernameFallbackKey) {
            this.currentUsernameFallbackKey = currentUsernameFallbackKey;
        }

        public Duration getRefreshTtl() {
            return refreshTtl;
        }

        public void setRefreshTtl(Duration refreshTtl) {
            this.refreshTtl = refreshTtl;
        }
    }

    public static class Revision {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Mongo {
        private boolean enabled = true;
        private boolean deferredIndexesEnabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isDeferredIndexesEnabled() {
            return deferredIndexesEnabled;
        }

        public void setDeferredIndexesEnabled(boolean deferredIndexesEnabled) {
            this.deferredIndexesEnabled = deferredIndexesEnabled;
        }
    }

    public static class Meilisearch {
        private boolean enabled = true;
        private boolean initializeIndexes = true;
        private String hostUrl;
        private String apiKey;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isInitializeIndexes() {
            return initializeIndexes;
        }

        public void setInitializeIndexes(boolean initializeIndexes) {
            this.initializeIndexes = initializeIndexes;
        }

        public String getHostUrl() {
            return hostUrl;
        }

        public void setHostUrl(String hostUrl) {
            this.hostUrl = hostUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    public static class Graphql {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}

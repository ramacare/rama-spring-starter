package org.rama.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "meilisearch")
public class MeilisearchProperties {
    private boolean enabled = true;
    private boolean initializeIndexes = true;
    private String host;
    private String masterKey;
}

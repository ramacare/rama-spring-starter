package org.rama.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "meilisearch")
public class MeilisearchProperties {
    private String host;
    private String masterKey;
}

package org.rama.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "encrypt")
public class EncryptProperties {
    private String key;
}

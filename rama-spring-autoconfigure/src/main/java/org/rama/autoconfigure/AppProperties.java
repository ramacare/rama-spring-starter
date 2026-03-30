package org.rama.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String fileStoragePath = "./data";
    private String fileStorageLocation = "s3";
}

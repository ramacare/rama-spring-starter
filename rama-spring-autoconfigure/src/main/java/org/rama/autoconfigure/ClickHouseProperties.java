package org.rama.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "rama.revision.clickhouse")
public class ClickHouseProperties {
    private boolean enabled = false;
    private String url;
    private String username;
    private String password;
    private String tableName = "revision";
    private int drainBatchSize = 1000;
    private Duration drainInterval = Duration.ofSeconds(30);
}

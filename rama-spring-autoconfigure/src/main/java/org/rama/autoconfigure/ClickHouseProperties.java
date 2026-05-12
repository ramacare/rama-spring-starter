package org.rama.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "rama.revision.clickhouse")
public class ClickHouseProperties {

    /** Master switch. When false, no ClickHouse beans are created and behavior is unchanged. */
    private boolean enabled = false;

    /** Full JDBC URL, e.g. jdbc:ch://localhost:8123/audit */
    private String url;

    private String username;

    private String password;

    /** ClickHouse table name. Same name across all consumers. */
    private String tableName = "revision";

    /** Max rows buffered before forced flush. */
    private int batchSize = 1000;

    /** Wall-clock interval between scheduled flushes (whether or not batchSize is reached). */
    private Duration flushInterval = Duration.ofSeconds(5);

    /** Max buffered rows before back-pressure (drop oldest with warning). */
    private int maxQueueSize = 100_000;
}

package org.rama.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rama.liquibase")
public class RamaStarterLiquibaseProperties {
    public static final String DEFAULT_CHANGE_LOG = "classpath:/db/changelog/rama-spring-starter-master.yaml";

    private boolean enabled = true;
    private String changeLog = DEFAULT_CHANGE_LOG;
}

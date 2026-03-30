package org.rama.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rama.liquibase")
public class RamaStarterLiquibaseProperties {
    private boolean enabled = true;
    private String changeLog = "classpath:/db/changelog/rama-spring-starter-master.yaml";
}

package org.rama.starter.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rama.starter.liquibase")
public class RamaStarterLiquibaseProperties {
    private boolean enabled = true;
    private String changeLog = "classpath:/db/changelog/rama-spring-starter-master.xml";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getChangeLog() {
        return changeLog;
    }

    public void setChangeLog(String changeLog) {
        this.changeLog = changeLog;
    }
}

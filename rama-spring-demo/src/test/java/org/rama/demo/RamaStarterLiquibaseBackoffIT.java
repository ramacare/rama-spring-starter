package org.rama.demo;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for starter#13.
 *
 * <p>Pre-fix: the starter unconditionally registered {@code ramaStarterLiquibase}
 * as a {@link SpringLiquibase} bean. Spring Boot's default {@code liquibase}
 * bean is guarded by {@code @ConditionalOnMissingBean(SpringLiquibase.class)},
 * so the starter's bean silently displaced it — the consumer's
 * {@code spring.liquibase.change-log} was ignored and only the starter's own
 * changelog ran. Latent in <= 4.0.22; surfaced by 4.0.23's auto-config reorder.
 *
 * <p>Post-fix: {@code ramaStarterLiquibase} is guarded by
 * {@code @ConditionalOnMissingBean(SpringLiquibase.class)} and backs off when
 * Spring Boot's default is active.
 *
 * <p>The demo configures {@code spring.liquibase.change-log} so Spring Boot's
 * default is active. This test asserts that (a) exactly one SpringLiquibase
 * bean exists, (b) it's Spring Boot's {@code liquibase}, and (c) the starter's
 * bean is not registered.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
class RamaStarterLiquibaseBackoffIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void starterLiquibaseBacksOffWhenConsumerConfiguresSpringBootDefault() {
        Map<String, SpringLiquibase> liquibaseBeans = context.getBeansOfType(SpringLiquibase.class);

        // Exactly one SpringLiquibase bean — Spring Boot's default runs the
        // consumer's changelog (which <include>s rama-spring-starter-master.yaml).
        assertThat(liquibaseBeans).containsOnlyKeys("liquibase");
        assertThat(context.containsBean("ramaStarterLiquibase")).isFalse();
    }
}

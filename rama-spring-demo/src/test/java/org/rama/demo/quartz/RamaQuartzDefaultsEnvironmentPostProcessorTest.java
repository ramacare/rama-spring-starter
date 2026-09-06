package org.rama.demo.quartz;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.autoconfigure.quartz.RamaQuartzDefaultsEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the starter's Quartz defaults (starter#49).
 *
 * <p>The defaults used to be attached with {@code @PropertySource} on
 * {@code RamaStarterAutoConfiguration}, which lands in the environment only when that class is
 * parsed — after Boot has already evaluated the {@code @ConditionalOnProperty} on
 * {@code QuartzAutoConfiguration.JdbcStoreTypeConfiguration}. {@code job-store-type=jdbc} was
 * therefore invisible and Quartz fell back to {@code RAMJobStore}. An
 * {@code EnvironmentPostProcessor} runs early enough; these tests pin the behaviour that made it
 * necessary.
 *
 * <p>Driver-delegate selection is not here: it depends on the live {@code DataSource}, so it lives
 * in {@code RamaQuartzDriverDelegateAutoConfiguration} and is covered by
 * {@link RamaQuartzDriverDelegateCustomizerTest}.
 */
@Tag("unit")
class RamaQuartzDefaultsEnvironmentPostProcessorTest {

    private final RamaQuartzDefaultsEnvironmentPostProcessor processor =
            new RamaQuartzDefaultsEnvironmentPostProcessor();

    @Test
    void postProcess_contributesJdbcJobStore() {
        StandardEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.quartz.job-store-type"))
                .as("the whole point: the property has to be here before any condition reads it")
                .isEqualTo("jdbc");
        assertThat(environment.getProperty("spring.quartz.properties.org.quartz.jobStore.isClustered"))
                .isEqualTo("true");
    }

    @Test
    void postProcess_whenJobStoreIsMemory_withholdsTheJdbcOnlyDefaults() {
        StandardEnvironment environment = environmentWith(Map.of(
                "spring.quartz.job-store-type", "memory"));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.quartz.properties.org.quartz.jobStore.isClustered"))
                .as("RAMJobStore has no setter for it and Quartz treats that as fatal; property "
                        + "layering can override a value but never remove one")
                .isNull();
        assertThat(environment.getProperty("spring.quartz.properties.org.quartz.jobStore.tablePrefix")).isNull();
        assertThat(environment.getProperty("spring.quartz.properties.org.quartz.threadPool.threadCount"))
                .as("engine-agnostic defaults still apply")
                .isEqualTo("5");
    }

    @Test
    void postProcess_whenDisabled_contributesNothing() {
        StandardEnvironment environment = environmentWith(Map.of("rama.quartz.apply-defaults", "false"));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.quartz.job-store-type")).isNull();
    }

    private StandardEnvironment environmentWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }
}

package org.rama.demo.quartz;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.autoconfigure.quartz.RamaQuartzDriverDelegateAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.quartz.autoconfigure.JobStoreType;
import org.springframework.boot.quartz.autoconfigure.QuartzProperties;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Properties;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Quartz's {@code StdJDBCDelegate} reads {@code JOB_DATA} with {@code getBlob}, which on PostgreSQL
 * means an OID large object, while the starter's changelog provisions {@code BYTEA} — mismatched,
 * the scheduler cannot store a single job (starter#49).
 *
 * <p>The engine is read from {@link DatabaseMetaData}, not from {@code spring.datasource.url}: the
 * URL is not in the environment at all when the {@code DataSource} is built as a bean or its URL
 * arrives from a config server, and a silent miss there would put PostgreSQL consumers straight
 * back where they started.
 */
@Tag("unit")
class RamaQuartzDriverDelegateCustomizerTest {

    private static final String DELEGATE_KEY = "org.quartz.jobStore.driverDelegateClass";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RamaQuartzDriverDelegateAutoConfiguration.class));

    @Test
    void onPostgres_setsThePostgresDelegate() {
        run(dataSourceReporting("PostgreSQL"), jdbc(), supplied ->
                assertThat(supplied).containsEntry(DELEGATE_KEY,
                        "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate"));
    }

    @Test
    void onOtherEngines_leavesQuartzOnItsOwnDefault() {
        for (String product : new String[]{"H2", "MySQL", "Microsoft SQL Server", "MariaDB"}) {
            run(dataSourceReporting(product), jdbc(), supplied ->
                    assertThat(supplied)
                            .as("%s is correct on StdJDBCDelegate; do not pin it to something untested", product)
                            .isNull());
        }
    }

    @Test
    void whenConsumerSetsADelegate_doesNotOverrideIt() {
        QuartzProperties properties = jdbc();
        properties.getProperties().put(DELEGATE_KEY, "com.example.MyDelegate");

        run(dataSourceReporting("PostgreSQL"), properties, supplied ->
                assertThat(supplied)
                        .as("must not re-supply properties at all once the consumer has chosen")
                        .isNull());
    }

    @Test
    void whenJobStoreIsNotJdbc_doesNothing() {
        QuartzProperties properties = jdbc();
        properties.setJobStoreType(JobStoreType.MEMORY);

        run(dataSourceReporting("PostgreSQL"), properties, supplied ->
                assertThat(supplied)
                        .as("a driver delegate is a JDBC-job-store concept; RAMJobStore rejects it")
                        .isNull());
    }

    @Test
    void whenMetadataCannotBeRead_leavesQuartzAloneRatherThanFailingStartup() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("pool exhausted"));

        run(dataSource, jdbc(), supplied ->
                assertThat(supplied)
                        .as("the consumer can always set the delegate by hand; do not take the context down")
                        .isNull());
    }

    @Test
    void whenDefaultsAreDisabled_theCustomizerIsNotRegisteredAtAll() {
        runner.withPropertyValues("rama.quartz.apply-defaults=false")
                .withBean(DataSource.class, () -> dataSourceReporting("PostgreSQL"))
                .withBean(QuartzProperties.class, this::jdbc)
                .run(context -> assertThat(context).doesNotHaveBean(SchedulerFactoryBeanCustomizer.class));
    }

    /** Runs the customizer the auto-configuration registered, and hands over what it supplied. */
    private void run(DataSource dataSource, QuartzProperties properties, Consumer<Properties> assertion) {
        runner.withBean(DataSource.class, () -> dataSource)
                .withBean(QuartzProperties.class, () -> properties)
                .run(context -> {
                    assertThat(context).hasSingleBean(SchedulerFactoryBeanCustomizer.class);
                    Properties[] supplied = new Properties[1];
                    SchedulerFactoryBean factoryBean = mock(SchedulerFactoryBean.class);
                    doAnswer(invocation -> {
                        supplied[0] = invocation.getArgument(0);
                        return null;
                    }).when(factoryBean).setQuartzProperties(any());

                    context.getBean(SchedulerFactoryBeanCustomizer.class).customize(factoryBean);
                    assertion.accept(supplied[0]);
                });
    }

    private QuartzProperties jdbc() {
        QuartzProperties properties = new QuartzProperties();
        properties.setJobStoreType(JobStoreType.JDBC);
        properties.getProperties().put("org.quartz.jobStore.isClustered", "true");
        return properties;
    }

    private DataSource dataSourceReporting(String databaseProductName) {
        try {
            DatabaseMetaData metaData = mock(DatabaseMetaData.class);
            when(metaData.getDatabaseProductName()).thenReturn(databaseProductName);
            Connection connection = mock(Connection.class);
            when(connection.getMetaData()).thenReturn(metaData);
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            return dataSource;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}

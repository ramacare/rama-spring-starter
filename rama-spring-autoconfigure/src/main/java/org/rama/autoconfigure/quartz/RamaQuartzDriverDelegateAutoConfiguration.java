package org.rama.autoconfigure.quartz;

import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.quartz.autoconfigure.QuartzProperties;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Picks the Quartz JDBC driver delegate to match the database the {@code DataSource} points at.
 *
 * <p>Quartz's default {@code StdJDBCDelegate} reads {@code JOB_DATA} and {@code CALENDAR} with
 * {@code getBlob}, which on PostgreSQL means an OID large object. The starter's
 * {@code rama-spring-quartz.changelog.xml} provisions those columns as {@code BYTEA}, because
 * {@code BLOB} on PostgreSQL would mean the OID form. Mismatched, the scheduler cannot store a
 * single job:
 *
 * <pre>
 * Couldn't store trigger 'rama-idempotency.system-request-dedup-cleanup-trigger' … :
 *   Bad value for type long : \xaced0005737200156f72672e71756172747a2e4a6f62446174614d6170…
 * </pre>
 *
 * <p>{@code PostgreSQLDelegate} reads the same columns with {@code getBytes}. H2, MySQL/MariaDB and
 * SQL Server are all correct on Quartz's own default, so only PostgreSQL is special-cased — every
 * other engine is left alone rather than being pinned to a delegate we have not tested it against.
 *
 * <p><strong>Why a customizer and not a property default.</strong> The engine is a property of the
 * live {@code DataSource}, not of configuration: the JDBC URL may be absent from the environment
 * entirely when the {@code DataSource} is built as a bean or its URL arrives from a config server
 * or Vault. {@link DatabaseMetaData#getDatabaseProductName()} is the authority, and a
 * {@link SchedulerFactoryBeanCustomizer} is the first point at which it can be consulted. This sits
 * on a <em>top-level</em> auto-configuration for the reason recorded in {@code CLAUDE.md}: anything
 * that inspects beans belongs on a top-level {@code @Bean} method, never inside a nested member
 * {@code @Configuration}.
 *
 * <p>Contrast {@link RamaQuartzDefaultsEnvironmentPostProcessor}, which cannot be a bean at all —
 * {@code spring.quartz.job-store-type} is read by a parse-phase condition, long before any bean
 * exists. The two halves of the Quartz defaults genuinely need two different lifecycle phases.
 *
 * <p>Consumers keep the last word: an explicit
 * {@code spring.quartz.properties.org.quartz.jobStore.driverDelegateClass} is never overwritten,
 * and {@code rama.quartz.apply-defaults=false} disables this along with the rest. See starter#49.
 */
@Slf4j
@AutoConfiguration(afterName = "org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration")
@ConditionalOnClass({Scheduler.class, SchedulerFactoryBean.class})
@ConditionalOnProperty(prefix = "rama.quartz", name = "apply-defaults", havingValue = "true", matchIfMissing = true)
public class RamaQuartzDriverDelegateAutoConfiguration {

    static final String DELEGATE_KEY = "org.quartz.jobStore.driverDelegateClass";
    static final String POSTGRES_DELEGATE = "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate";

    /**
     * Ordered just after Boot's own {@code dataSourceCustomizer} ({@code @Order(0)}) and well ahead
     * of an unordered consumer customizer, which defaults to {@code LOWEST_PRECEDENCE}. That
     * matters because {@link SchedulerFactoryBean} exposes no getter for its Quartz properties, so
     * contributing one means re-supplying the whole {@link Properties} — running early means a
     * consumer's customizer still gets the final say.
     */
    @Bean
    @Order(1)
    SchedulerFactoryBeanCustomizer ramaQuartzDriverDelegateCustomizer(
            ObjectProvider<QuartzProperties> quartzPropertiesProvider,
            ObjectProvider<DataSource> dataSourceProvider) {
        return schedulerFactoryBean -> {
            // ObjectProvider, not a hard dependency or a @ConditionalOnBean: QuartzProperties comes
            // from Boot's QuartzAutoConfiguration, which a consumer may exclude outright. This bean
            // is inert in that case anyway — nothing collects customizers without a scheduler.
            QuartzProperties quartzProperties = quartzPropertiesProvider.getIfAvailable();
            if (quartzProperties == null) {
                return;
            }
            Map<String, String> properties = new LinkedHashMap<>(quartzProperties.getProperties());
            if (properties.containsKey(DELEGATE_KEY)) {
                return;
            }
            // A delegate is a JDBC-job-store concept; RAMJobStore rejects it outright.
            if (quartzProperties.getJobStoreType() != org.springframework.boot.quartz.autoconfigure.JobStoreType.JDBC) {
                return;
            }
            DataSource dataSource = dataSourceProvider.getIfAvailable();
            if (dataSource == null) {
                return;
            }
            String delegate = delegateFor(dataSource);
            if (delegate == null) {
                return;
            }
            log.info("Quartz: using {} for {}", delegate, DELEGATE_KEY);
            properties.put(DELEGATE_KEY, delegate);
            Properties merged = new Properties();
            merged.putAll(properties);
            schedulerFactoryBean.setQuartzProperties(merged);
        };
    }

    /**
     * @return the delegate this engine needs, or {@code null} when Quartz's own default is right.
     */
    private String delegateFor(DataSource dataSource) {
        String product;
        try {
            product = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName);
        } catch (Exception ex) {
            // Never fail startup over this: the consumer can always set the delegate by hand.
            log.warn("Quartz: could not read database metadata to choose a driver delegate; "
                    + "leaving Quartz on StdJDBCDelegate. Set {} explicitly if this database needs "
                    + "a different one.", DELEGATE_KEY, ex);
            return null;
        }
        // H2 reports "H2" even in PostgreSQL compatibility MODE, which is correct here: its BLOB
        // columns still read through getBlob.
        return (product != null && product.toLowerCase().contains("postgresql")) ? POSTGRES_DELEGATE : null;
    }
}

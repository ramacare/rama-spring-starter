package org.rama.autoconfigure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.JobBuilder;
import org.rama.clickhouse.ClickHouseRevisionDispatcher;
import org.rama.clickhouse.ClickHouseSchemaInitializer;
import org.rama.clickhouse.RevisionClickHouseRepository;
import org.rama.job.system.SystemBufferDrainJob;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

@AutoConfiguration
@ConditionalOnProperty(prefix = "rama.revision.clickhouse", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ClickHouseProperties.class)
public class RamaStarterClickHouseAutoConfiguration {

    @Bean(name = "clickHouseDataSource", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "clickHouseDataSource")
    DataSource clickHouseDataSource(ClickHouseProperties properties) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(properties.getUrl());
        cfg.setUsername(properties.getUsername());
        cfg.setPassword(properties.getPassword());
        cfg.setPoolName("clickhouse-revision-pool");
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(0);
        cfg.setInitializationFailTimeout(-1); // don't fail boot if CH is unreachable
        return new HikariDataSource(cfg);
    }

    @Bean(name = "clickHouseJdbcTemplate")
    @ConditionalOnMissingBean(name = "clickHouseJdbcTemplate")
    JdbcTemplate clickHouseJdbcTemplate(DataSource clickHouseDataSource) {
        return new JdbcTemplate(clickHouseDataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    ClickHouseSchemaInitializer clickHouseSchemaInitializer(
            JdbcTemplate clickHouseJdbcTemplate, ClickHouseProperties properties) {
        return new ClickHouseSchemaInitializer(clickHouseJdbcTemplate, properties.getTableName());
    }

    @Bean
    @ConditionalOnMissingBean
    RevisionClickHouseRepository revisionClickHouseRepository(
            JdbcTemplate clickHouseJdbcTemplate, ClickHouseProperties properties) {
        return new RevisionClickHouseRepository(clickHouseJdbcTemplate, properties.getTableName());
    }

    @Bean
    @ConditionalOnMissingBean
    ClickHouseRevisionDispatcher clickHouseRevisionDispatcher(
            JdbcTemplate clickHouseJdbcTemplate, ClickHouseProperties properties,
            ObjectMapper objectMapper) {
        return new ClickHouseRevisionDispatcher(
                clickHouseJdbcTemplate, properties.getTableName(), objectMapper);
    }

    /**
     * Registers the drain job with Quartz via Spring Boot's JobDetail bean support.
     * Spring Boot's Quartz auto-configuration picks up all JobDetail and Trigger beans
     * and registers them with the Scheduler automatically.
     */
    @Bean
    @ConditionalOnMissingBean(name = "systemBufferDrainJobDetail")
    JobDetail systemBufferDrainJobDetail(ClickHouseProperties properties) {
        return JobBuilder.newJob(SystemBufferDrainJob.class)
                .withIdentity("systemBufferDrain", "system")
                .storeDurably()
                .requestRecovery(true)
                .usingJobData(SystemBufferDrainJob.KEY_BATCH_SIZE, properties.getDrainBatchSize())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(name = "systemBufferDrainTrigger")
    Trigger systemBufferDrainTrigger(JobDetail systemBufferDrainJobDetail, ClickHouseProperties properties) {
        int seconds = Math.max(1, (int) properties.getDrainInterval().toSeconds());
        return TriggerBuilder.newTrigger()
                .forJob(systemBufferDrainJobDetail)
                .withIdentity("systemBufferDrainTrigger", "system")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(seconds)
                        .repeatForever())
                .build();
    }
}

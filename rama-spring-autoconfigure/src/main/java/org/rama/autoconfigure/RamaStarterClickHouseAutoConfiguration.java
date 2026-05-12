package org.rama.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import org.rama.clickhouse.ClickHouseSchemaInitializer;
import org.rama.clickhouse.RevisionClickHouseBackfillJob;
import org.rama.clickhouse.RevisionClickHouseRepository;
import org.rama.clickhouse.RevisionClickHouseSink;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

@AutoConfiguration
@EnableConfigurationProperties(ClickHouseProperties.class)
@ConditionalOnProperty(prefix = "rama.revision.clickhouse", name = "enabled", havingValue = "true")
@EnableScheduling
public class RamaStarterClickHouseAutoConfiguration {

    @Bean(name = "clickHouseDataSource", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "clickHouseDataSource")
    public DataSource clickHouseDataSource(ClickHouseProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        ds.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        // ClickHouse is mostly long batched inserts; one or two connections is plenty.
        ds.setMaximumPoolSize(4);
        ds.setMinimumIdle(1);
        ds.setPoolName("rama-clickhouse");
        // Critical for fail-soft: -1 disables HikariCP's startup connectivity check, so the
        // bean is created even when ClickHouse is unreachable at app boot. The first real
        // operation (schema init / flush) will catch the connection error and log it.
        ds.setInitializationFailTimeout(-1);
        // Bounded wait for a connection so flush failures surface quickly instead of
        // blocking the scheduled thread for the full default 30 s.
        ds.setConnectionTimeout(5_000);
        return ds;
    }

    @Bean(name = "clickHouseJdbcTemplate")
    @ConditionalOnMissingBean(name = "clickHouseJdbcTemplate")
    public JdbcTemplate clickHouseJdbcTemplate(
            @Qualifier("clickHouseDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    @ConditionalOnMissingBean
    public ClickHouseSchemaInitializer clickHouseSchemaInitializer(
            @Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc,
            ClickHouseProperties props) {
        return new ClickHouseSchemaInitializer(jdbc, props.getTableName());
    }

    @Bean
    @ConditionalOnMissingBean
    public RevisionClickHouseSink revisionClickHouseSink(
            @Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc,
            ClickHouseProperties props) {
        return new RevisionClickHouseSink(jdbc, props.getTableName(), props.getBatchSize(), props.getMaxQueueSize());
    }

    @Bean
    @ConditionalOnMissingBean
    public RevisionClickHouseRepository revisionClickHouseRepository(
            @Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc,
            ClickHouseProperties props) {
        return new RevisionClickHouseRepository(jdbc, props.getTableName());
    }

    @Bean
    @ConditionalOnMissingBean
    public RevisionClickHouseBackfillJob revisionClickHouseBackfillJob(
            JdbcTemplate jdbcTemplate,
            @Qualifier("clickHouseJdbcTemplate") JdbcTemplate chJdbc,
            RevisionClickHouseSink sink,
            ClickHouseProperties props) {
        return new RevisionClickHouseBackfillJob(jdbcTemplate, chJdbc, sink, props.getTableName());
    }
}

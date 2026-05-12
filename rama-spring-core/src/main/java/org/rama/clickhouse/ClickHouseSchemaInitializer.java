package org.rama.clickhouse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs once on application startup. Creates the revision table in
 * ClickHouse if it does not already exist. Idempotent: CREATE TABLE IF
 * NOT EXISTS is the only DDL.
 *
 * <p>Liquibase is not used here -- its ClickHouse support is experimental
 * (community extension) and changes table dialect by design. A direct
 * idempotent DDL is simpler and matches the "schema is part of the
 * starter, not the consumer" contract for this audit table.
 */
@Slf4j
@RequiredArgsConstructor
public class ClickHouseSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate clickHouseJdbcTemplate;
    private final String tableName;

    @Override
    public void run(ApplicationArguments args) {
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                    id              UInt64,
                    revision_key    String,
                    mrn             LowCardinality(Nullable(String)),
                    revision_entity LowCardinality(Nullable(String)),
                    revision_datetime DateTime64(3, 'UTC'),
                    revision_data   String CODEC(ZSTD(3)),
                    revision_change Nullable(String) CODEC(ZSTD(3)),
                    created_by      LowCardinality(Nullable(String)),
                    updated_by      LowCardinality(Nullable(String)),
                    created_at      Nullable(DateTime64(3, 'UTC')),
                    updated_at      Nullable(DateTime64(3, 'UTC'))
                )
                ENGINE = MergeTree()
                PARTITION BY toYYYYMM(revision_datetime)
                ORDER BY (revision_key, revision_datetime, id)
                SETTINGS index_granularity = 8192
                """.formatted(safeIdent(tableName));

        log.info("Initializing ClickHouse table {}", tableName);
        try {
            clickHouseJdbcTemplate.execute(ddl);
        } catch (RuntimeException e) {
            // Fail-soft: ClickHouse being unreachable at startup must NOT prevent
            // the application from booting. The SQL `revision` table remains the
            // source of truth; ClickHouse will catch up on the next flush attempt
            // or via the RevisionClickHouseBackfillJob.
            log.error("Failed to initialize ClickHouse table {} — continuing without it. "
                    + "Will retry on next flush. Cause: {}", tableName, e.getMessage());
        }
    }

    static String safeIdent(String s) {
        if (s == null || !s.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new IllegalArgumentException("Unsafe ClickHouse table name: " + s);
        }
        return s;
    }
}

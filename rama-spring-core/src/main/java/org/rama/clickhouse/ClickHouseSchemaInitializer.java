package org.rama.clickhouse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
@RequiredArgsConstructor
public class ClickHouseSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate clickHouseJdbcTemplate;
    private final String tableName;

    @Override
    public void run(ApplicationArguments args) {
        String safe = safeIdent(tableName);
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                    revision_key       String,
                    revision_datetime  DateTime64(3, 'UTC'),
                    revision_entity    LowCardinality(String),
                    mrn                LowCardinality(Nullable(String)),
                    revision_data      String CODEC(ZSTD(3)),
                    revision_change    Nullable(String) CODEC(ZSTD(3)),
                    created_by         LowCardinality(Nullable(String)),
                    updated_by         LowCardinality(Nullable(String)),
                    ingested_at        DateTime64(3, 'UTC') DEFAULT now64(3),
                    INDEX idx_mrn        mrn             TYPE bloom_filter GRANULARITY 4,
                    INDEX idx_entity     revision_entity TYPE bloom_filter GRANULARITY 4,
                    INDEX idx_created_by created_by      TYPE bloom_filter GRANULARITY 4
                )
                ENGINE = ReplacingMergeTree(ingested_at)
                PARTITION BY toYYYYMM(revision_datetime)
                ORDER BY (revision_key, revision_datetime)
                SETTINGS index_granularity = 8192
                """.formatted(safe);

        log.info("Initializing ClickHouse table {}", tableName);
        try {
            clickHouseJdbcTemplate.execute(ddl);
        } catch (RuntimeException e) {
            log.error("Failed to initialize ClickHouse table {} — continuing. Cause: {}",
                    tableName, e.getMessage());
        }
    }

    static String safeIdent(String s) {
        if (s == null || !s.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new IllegalArgumentException("Unsafe ClickHouse table name: " + s);
        }
        return s;
    }
}

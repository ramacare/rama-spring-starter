package org.rama.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ClickHouseSchemaInitializerTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApplicationArguments args;

    @Test
    void run_shouldExecuteCreateTableForRevision() throws Exception {
        ClickHouseSchemaInitializer init = new ClickHouseSchemaInitializer(jdbcTemplate, "revision");

        init.run(args);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sql.capture());

        String stmt = sql.getValue();
        assertThat(stmt)
                .contains("CREATE TABLE IF NOT EXISTS revision")
                .contains("ENGINE = MergeTree()")
                .contains("PARTITION BY toYYYYMM(revision_datetime)")
                .contains("ORDER BY (revision_key, revision_datetime, id)")
                .contains("CODEC(ZSTD(3))")
                .contains("LowCardinality(Nullable(String))");
    }

    @Test
    void run_shouldUseConfiguredTableName() throws Exception {
        ClickHouseSchemaInitializer init = new ClickHouseSchemaInitializer(jdbcTemplate, "audit_revision");

        init.run(args);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sql.capture());
        assertThat(sql.getValue()).contains("CREATE TABLE IF NOT EXISTS audit_revision");
    }

    @Test
    void run_shouldSwallowConnectionFailures_andReturnNormally() {
        ClickHouseSchemaInitializer init = new ClickHouseSchemaInitializer(jdbcTemplate, "revision");
        doThrow(new DataAccessResourceFailureException("ClickHouse unreachable"))
                .when(jdbcTemplate).execute(anyString());

        // Critical: must NOT propagate. App startup must continue even if ClickHouse is down.
        assertThatNoException().isThrownBy(() -> init.run(args));
    }
}

package org.rama.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ClickHouseSchemaInitializerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void run_executesDdlWithExpectedSchemaKeywords() {
        ClickHouseSchemaInitializer initializer =
                new ClickHouseSchemaInitializer(jdbcTemplate, "revision");

        initializer.run(mock(ApplicationArguments.class));

        ArgumentCaptor<String> ddlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(ddlCaptor.capture());
        String ddl = ddlCaptor.getValue();

        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS revision");
        assertThat(ddl).contains("ENGINE = ReplacingMergeTree(ingested_at)");
        assertThat(ddl).contains("PARTITION BY toYYYYMM(revision_datetime)");
        assertThat(ddl).contains("ORDER BY (revision_key, revision_datetime)");
        assertThat(ddl).contains("CODEC(ZSTD(3))");
        assertThat(ddl).contains("INDEX idx_mrn");
        assertThat(ddl).contains("INDEX idx_entity");
        assertThat(ddl).contains("INDEX idx_created_by");
        assertThat(ddl).contains("bloom_filter");
    }

    @Test
    void run_usesConfiguredTableName() {
        ClickHouseSchemaInitializer initializer =
                new ClickHouseSchemaInitializer(jdbcTemplate, "audit_revision");

        initializer.run(mock(ApplicationArguments.class));

        ArgumentCaptor<String> ddlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(ddlCaptor.capture());
        assertThat(ddlCaptor.getValue()).contains("CREATE TABLE IF NOT EXISTS audit_revision");
    }

    @Test
    void run_failSoftOnRuntimeException() {
        doThrow(new RuntimeException("ClickHouse unavailable"))
                .when(jdbcTemplate).execute(anyString());
        ClickHouseSchemaInitializer initializer =
                new ClickHouseSchemaInitializer(jdbcTemplate, "revision");

        assertThatCode(() -> initializer.run(mock(ApplicationArguments.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void safeIdent_rejectsBadName() {
        assertThatThrownBy(() -> ClickHouseSchemaInitializer.safeIdent("revision; DROP"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsafe ClickHouse table name");
    }
}

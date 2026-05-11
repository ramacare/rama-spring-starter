package org.rama.job.revision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RevisionPartitionMaintenanceJobTest {

    @Mock private JdbcTemplate jdbcTemplate;

    private RevisionPartitionMaintenanceJob job;

    @BeforeEach
    void setUp() {
        job = new RevisionPartitionMaintenanceJob(jdbcTemplate);
    }

    @Test
    void extendNextMonthBoundary_shouldSplitWhenAbsent() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("pf_revision_monthly"), anyString()))
                .thenReturn(0);

        job.extendNextMonthBoundary("pf_revision_monthly");

        verify(jdbcTemplate).execute(startsWith(
                "ALTER PARTITION FUNCTION pf_revision_monthly() SPLIT RANGE ("));
    }

    @Test
    void extendNextMonthBoundary_shouldSkipWhenPresent() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("pf_revision_monthly"), anyString()))
                .thenReturn(1);

        job.extendNextMonthBoundary("pf_revision_monthly");

        verify(jdbcTemplate, never()).execute(startsWith("ALTER PARTITION FUNCTION"));
    }

    @Test
    void switchOldPartitions_shouldEmitSwitchForEachOverduePartition() {
        when(jdbcTemplate.queryForList(anyString(), eq("pf_revision_monthly"), anyString()))
                .thenReturn(List.of(
                        Map.of("partition_number", 1, "upper_bound", "2024-01-01T00:00Z"),
                        Map.of("partition_number", 2, "upper_bound", "2024-02-01T00:00Z")));

        job.switchOldPartitions("revision_active", "revision_archive", "pf_revision_monthly", 12);

        verify(jdbcTemplate).execute(
                "ALTER TABLE dbo.revision_active SWITCH PARTITION 1"
                        + " TO dbo.revision_archive PARTITION 1");
        verify(jdbcTemplate).execute(
                "ALTER TABLE dbo.revision_active SWITCH PARTITION 2"
                        + " TO dbo.revision_archive PARTITION 2");
    }

    @Test
    void switchOldPartitions_shouldNoop_whenNothingOverdue() {
        when(jdbcTemplate.queryForList(anyString(), eq("pf_revision_monthly"), anyString()))
                .thenReturn(List.of());

        job.switchOldPartitions("revision_active", "revision_archive", "pf_revision_monthly", 12);

        verify(jdbcTemplate, never()).execute(startsWith("ALTER TABLE"));
    }

    // ---------------- PostgreSQL ----------------

    @Test
    void ensureNextMonthPartitionPostgres_shouldCreate_whenAbsent() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString()))
                .thenReturn(0);

        job.ensureNextMonthPartitionPostgres("revision_active");

        verify(jdbcTemplate).execute(startsWith(
                "CREATE TABLE revision_active_"));
    }

    @Test
    void ensureNextMonthPartitionPostgres_shouldSkip_whenPresent() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString()))
                .thenReturn(1);

        job.ensureNextMonthPartitionPostgres("revision_active");

        verify(jdbcTemplate, never()).execute(startsWith("CREATE TABLE"));
    }

    @Test
    void detachAndAttachOldPartitionsPostgres_shouldDetachAndAttachEachOverdue() {
        when(jdbcTemplate.queryForList(anyString(), eq("revision_active")))
                .thenReturn(List.of(
                        Map.of("partition_name", "revision_active_2024_01",
                                "bounds", "FOR VALUES FROM ('2024-01-01') TO ('2024-02-01')"),
                        Map.of("partition_name", "revision_active_2024_02",
                                "bounds", "FOR VALUES FROM ('2024-02-01') TO ('2024-03-01')")));

        job.detachAndAttachOldPartitionsPostgres("revision_active", "revision_archive", 12);

        verify(jdbcTemplate).execute(
                "ALTER TABLE revision_active DETACH PARTITION revision_active_2024_01");
        verify(jdbcTemplate).execute(
                "ALTER TABLE revision_archive ATTACH PARTITION revision_active_2024_01"
                        + " FOR VALUES FROM ('2024-01-01') TO ('2024-02-01')");
        verify(jdbcTemplate).execute(
                "ALTER TABLE revision_active DETACH PARTITION revision_active_2024_02");
        verify(jdbcTemplate).execute(
                "ALTER TABLE revision_archive ATTACH PARTITION revision_active_2024_02"
                        + " FOR VALUES FROM ('2024-02-01') TO ('2024-03-01')");
    }

    @Test
    void detachAndAttachOldPartitionsPostgres_shouldSkipPartitionsInsideActiveWindow() {
        String farFuture = java.time.LocalDate.now()
                .plusYears(1).withDayOfMonth(1).toString();
        when(jdbcTemplate.queryForList(anyString(), eq("revision_active")))
                .thenReturn(List.of(
                        Map.of("partition_name", "revision_active_future",
                                "bounds", "FOR VALUES FROM ('" + farFuture + "') TO ('" + farFuture + "')")));

        job.detachAndAttachOldPartitionsPostgres("revision_active", "revision_archive", 12);

        verify(jdbcTemplate, never()).execute(startsWith("ALTER TABLE"));
    }

    @Test
    void parsePostgresUpperBound_shouldExtractDate() {
        java.time.LocalDate result = RevisionPartitionMaintenanceJob.parsePostgresUpperBound(
                "FOR VALUES FROM ('2025-03-01') TO ('2025-04-01')");
        org.assertj.core.api.Assertions.assertThat(result)
                .isEqualTo(java.time.LocalDate.of(2025, 4, 1));
    }

    @Test
    void parsePostgresUpperBound_shouldReturnNullOnMalformed() {
        org.assertj.core.api.Assertions.assertThat(
                RevisionPartitionMaintenanceJob.parsePostgresUpperBound(null)).isNull();
        org.assertj.core.api.Assertions.assertThat(
                RevisionPartitionMaintenanceJob.parsePostgresUpperBound("garbage")).isNull();
    }
}

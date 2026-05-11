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

    // ---------------- MySQL ----------------

    @Test
    void reorganizeNextMonthMysql_shouldEmitReorganize_whenPartitionAbsent() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("revision"), anyString()))
                .thenReturn(0);

        job.reorganizeNextMonthMysql("revision");

        verify(jdbcTemplate).execute(startsWith(
                "ALTER TABLE revision REORGANIZE PARTITION p_future INTO ("));
    }

    @Test
    void reorganizeNextMonthMysql_shouldSkip_whenPartitionPresent() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("revision"), anyString()))
                .thenReturn(1);

        job.reorganizeNextMonthMysql("revision");

        verify(jdbcTemplate, never()).execute(startsWith("ALTER TABLE"));
    }

    @Test
    void compressOldPartitionsMysql_shouldRebuildEachOverdue_skippingAlreadyCompressed() {
        when(jdbcTemplate.queryForList(anyString(), eq("revision"), anyString()))
                .thenReturn(List.of(
                        Map.of("pn", "p202401", "upper_bound", "'2024-02-01'"),
                        Map.of("pn", "p202402", "upper_bound", "'2024-03-01'")));
        // first partition: not yet compressed; second: already compressed (skip)
        when(jdbcTemplate.queryForObject(
                eq("SELECT row_format FROM information_schema.partitions"
                        + " WHERE table_schema = DATABASE() AND table_name = ? AND partition_name = ?"),
                eq(String.class), eq("revision"), eq("p202401")))
                .thenReturn("DYNAMIC");
        when(jdbcTemplate.queryForObject(
                eq("SELECT row_format FROM information_schema.partitions"
                        + " WHERE table_schema = DATABASE() AND table_name = ? AND partition_name = ?"),
                eq(String.class), eq("revision"), eq("p202402")))
                .thenReturn("COMPRESSED");

        job.compressOldPartitionsMysql("revision", 12);

        verify(jdbcTemplate).execute(
                "ALTER TABLE revision REBUILD PARTITION p202401, ROW_FORMAT=COMPRESSED");
        verify(jdbcTemplate, never()).execute(
                "ALTER TABLE revision REBUILD PARTITION p202402, ROW_FORMAT=COMPRESSED");
    }

    @Test
    void compressOldPartitionsMysql_shouldNoop_whenNothingOverdue() {
        when(jdbcTemplate.queryForList(anyString(), eq("revision"), anyString()))
                .thenReturn(List.of());

        job.compressOldPartitionsMysql("revision", 12);

        verify(jdbcTemplate, never()).execute(startsWith("ALTER TABLE"));
    }
}

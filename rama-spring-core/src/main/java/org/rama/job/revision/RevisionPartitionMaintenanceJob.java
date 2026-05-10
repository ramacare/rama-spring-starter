package org.rama.job.revision;

import lombok.extern.slf4j.Slf4j;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.quartz.QuartzJobBean;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Sliding-window maintenance for the bounded {@code revision_active} table.
 * On each run:
 *
 * <ol>
 *   <li>Extend the partition function with next month's boundary so writes
 *       at the start of next month land in a dedicated empty partition
 *       (otherwise SQL Server appends to the rightmost open range).</li>
 *   <li>For every partition whose upper bound is older than the configured
 *       {@code activeWindow} (default 12 months), SWITCH the partition out
 *       of {@code revision_active} into {@code revision_archive}. SWITCH is
 *       a metadata-only operation regardless of partition row count.</li>
 * </ol>
 *
 * <p>Schedule monthly (e.g. {@code 0 0 2 1 * ?} — 02:00 on the first day
 * of each month). Recovery from a missed run is automatic — the loop
 * catches up by switching every overdue partition.
 *
 * <p>JobDataMap keys:
 * <ul>
 *   <li>{@code activeWindowMonths} — int, default 12</li>
 *   <li>{@code activeTableName} — default {@code revision_active}</li>
 *   <li>{@code archiveTableName} — default {@code revision_archive}</li>
 *   <li>{@code partitionFunctionName} — default {@code pf_revision_monthly}</li>
 * </ul>
 */
@Slf4j
public class RevisionPartitionMaintenanceJob extends QuartzJobBean {

    public static final String KEY_ACTIVE_WINDOW_MONTHS = "activeWindowMonths";
    public static final String KEY_ACTIVE_TABLE = "activeTableName";
    public static final String KEY_ARCHIVE_TABLE = "archiveTableName";
    public static final String KEY_PARTITION_FN = "partitionFunctionName";

    private final JdbcTemplate jdbcTemplate;

    public RevisionPartitionMaintenanceJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        JobDataMap data = context.getMergedJobDataMap();
        int windowMonths = data.containsKey(KEY_ACTIVE_WINDOW_MONTHS)
                ? data.getIntValue(KEY_ACTIVE_WINDOW_MONTHS) : 12;
        String activeTable = orDefault(data.getString(KEY_ACTIVE_TABLE), "revision_active");
        String archiveTable = orDefault(data.getString(KEY_ARCHIVE_TABLE), "revision_archive");
        String partitionFn = orDefault(data.getString(KEY_PARTITION_FN), "pf_revision_monthly");

        extendNextMonthBoundary(partitionFn);
        switchOldPartitions(activeTable, archiveTable, partitionFn, windowMonths);
    }

    void extendNextMonthBoundary(String partitionFn) {
        YearMonth nextMonth = YearMonth.now(ZoneOffset.UTC).plusMonths(1);
        String boundary = nextMonth.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString();
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys.partition_functions pf"
                        + " JOIN sys.partition_range_values rv ON rv.function_id = pf.function_id"
                        + " WHERE pf.name = ? AND CAST(rv.value AS DATETIME2) = ?",
                Integer.class, partitionFn, boundary);
        if (exists != null && exists > 0) {
            log.debug("Partition boundary {} already exists on {}", boundary, partitionFn);
            return;
        }
        jdbcTemplate.execute(
                "ALTER PARTITION FUNCTION " + RevisionLegacyBackfillJob.safeIdent(partitionFn)
                        + "() SPLIT RANGE ('" + boundary + "')");
        log.info("Split partition function {} at {}", partitionFn, boundary);
    }

    void switchOldPartitions(String activeTable, String archiveTable, String partitionFn, int windowMonths) {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(windowMonths);

        List<Map<String, Object>> overdue = jdbcTemplate.queryForList(
                "SELECT rv.boundary_id AS partition_number, CAST(rv.value AS DATETIME2) AS upper_bound"
                        + " FROM sys.partition_functions pf"
                        + " JOIN sys.partition_range_values rv ON rv.function_id = pf.function_id"
                        + " WHERE pf.name = ? AND CAST(rv.value AS DATETIME2) <= ?"
                        + " ORDER BY rv.boundary_id ASC",
                partitionFn, cutoff.atStartOfDay().atOffset(ZoneOffset.UTC).toString());

        for (Map<String, Object> row : overdue) {
            Number partitionNumber = (Number) row.get("partition_number");
            jdbcTemplate.execute(
                    "ALTER TABLE dbo." + RevisionLegacyBackfillJob.safeIdent(activeTable)
                            + " SWITCH PARTITION " + partitionNumber.intValue()
                            + " TO dbo." + RevisionLegacyBackfillJob.safeIdent(archiveTable)
                            + " PARTITION " + partitionNumber.intValue());
            log.info("Switched partition {} from {} to {}", partitionNumber, activeTable, archiveTable);
        }
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}

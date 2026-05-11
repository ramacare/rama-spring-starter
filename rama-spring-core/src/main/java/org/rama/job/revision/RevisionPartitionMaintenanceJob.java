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
import java.util.Locale;
import java.util.Map;

/**
 * Sliding-window partition maintenance for the revision audit log.
 * Dispatches to a DBMS-specific implementation based on
 * {@link java.sql.DatabaseMetaData#getDatabaseProductName()}:
 *
 * <ul>
 *   <li><b>Microsoft SQL Server:</b> split the partition function with
 *       next month's boundary, then {@code ALTER TABLE ... SWITCH} every
 *       overdue partition from {@code revision_active} to
 *       {@code revision_archive}. Metadata-only operation.</li>
 *   <li><b>MySQL / MariaDB:</b> reorganize the trailing
 *       {@code p_future MAXVALUE} partition into next month's bounded
 *       partition plus a new {@code p_future}, then
 *       {@code ALTER TABLE ... REBUILD PARTITION ... ROW_FORMAT=COMPRESSED}
 *       for every partition older than the active window. Single table,
 *       no archive table.</li>
 *   <li><b>PostgreSQL:</b> (to be added) DETACH old partitions from
 *       {@code revision_active} and ATTACH them under
 *       {@code revision_archive}. Metadata-only.</li>
 *   <li>Other DBMS / fallback: log and return — no partition maintenance
 *       applicable.</li>
 * </ul>
 *
 * <p>Schedule monthly (e.g. {@code 0 0 2 1 * ?} — 02:00 on the first day
 * of each month). Recovery from a missed run is automatic — the loop
 * catches up by acting on every overdue partition.
 *
 * <p>JobDataMap keys (consumed per-DBMS as relevant):
 * <ul>
 *   <li>{@code activeWindowMonths} — int, default 12</li>
 *   <li>{@code activeTableName} — default {@code revision_active} (MSSQL)
 *       or {@code revision} (MySQL)</li>
 *   <li>{@code archiveTableName} — default {@code revision_archive}
 *       (MSSQL only)</li>
 *   <li>{@code partitionFunctionName} — default {@code pf_revision_monthly}
 *       (MSSQL only)</li>
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
        String product = jdbcTemplate.execute((java.sql.Connection c) ->
                c.getMetaData().getDatabaseProductName());
        if (product == null) product = "";
        switch (product.toLowerCase(Locale.ROOT)) {
            case "microsoft sql server" -> maintainMssql(context);
            case "mysql", "mariadb"     -> maintainMysql(context);
            default                     -> log.info(
                    "RevisionPartitionMaintenanceJob: no maintenance defined for DBMS {}", product);
        }
    }

    // ---------------- MSSQL ----------------

    void maintainMssql(JobExecutionContext context) {
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

    // ---------------- MySQL / MariaDB ----------------

    void maintainMysql(JobExecutionContext context) {
        JobDataMap data = context.getMergedJobDataMap();
        int windowMonths = data.containsKey(KEY_ACTIVE_WINDOW_MONTHS)
                ? data.getIntValue(KEY_ACTIVE_WINDOW_MONTHS) : 12;
        String table = orDefault(data.getString(KEY_ACTIVE_TABLE), "revision");

        reorganizeNextMonthMysql(table);
        compressOldPartitionsMysql(table, windowMonths);
    }

    /**
     * Split the trailing {@code p_future} (MAXVALUE) partition into a
     * dedicated bounded partition for next month plus a new {@code p_future}.
     * No-op when next month's partition already exists.
     */
    void reorganizeNextMonthMysql(String table) {
        YearMonth nextMonth = YearMonth.now(ZoneOffset.UTC).plusMonths(1);
        YearMonth monthAfter = nextMonth.plusMonths(1);
        String partitionName = "p" + nextMonth.toString().replace("-", "");
        String boundary = monthAfter.atDay(1).toString();

        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.partitions"
                        + " WHERE table_schema = DATABASE() AND table_name = ? AND partition_name = ?",
                Integer.class, table, partitionName);
        if (exists != null && exists > 0) {
            log.debug("Partition {} already exists on table {}", partitionName, table);
            return;
        }

        String ident = RevisionLegacyBackfillJob.safeIdent(table);
        String partIdent = RevisionLegacyBackfillJob.safeIdent(partitionName);
        jdbcTemplate.execute(
                "ALTER TABLE " + ident + " REORGANIZE PARTITION p_future INTO ("
                        + " PARTITION " + partIdent + " VALUES LESS THAN ('" + boundary + "'),"
                        + " PARTITION p_future VALUES LESS THAN (MAXVALUE))");
        log.info("Reorganized {} to add partition {} (< {})", table, partitionName, boundary);
    }

    /**
     * Rebuild any partition older than the active window with
     * {@code ROW_FORMAT=COMPRESSED}. MySQL's only practical compression
     * lever for this scenario; ~2-3× on JSON-heavy revision data.
     */
    void compressOldPartitionsMysql(String table, int windowMonths) {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(windowMonths);
        String cutoffStr = cutoff.toString();

        List<Map<String, Object>> overdue = jdbcTemplate.queryForList(
                "SELECT partition_name AS pn, partition_description AS upper_bound"
                        + " FROM information_schema.partitions"
                        + " WHERE table_schema = DATABASE() AND table_name = ?"
                        + "   AND partition_name <> 'p_future'"
                        + "   AND partition_description IS NOT NULL"
                        + "   AND partition_description != 'MAXVALUE'"
                        + "   AND STR_TO_DATE(REPLACE(REPLACE(partition_description, '''', ''), '\"', ''), '%Y-%m-%d') <= ?"
                        + " ORDER BY partition_ordinal_position ASC",
                table, cutoffStr);

        String ident = RevisionLegacyBackfillJob.safeIdent(table);
        for (Map<String, Object> row : overdue) {
            String partitionName = (String) row.get("pn");
            // Check current row format -- skip if already compressed.
            String currentFormat = jdbcTemplate.queryForObject(
                    "SELECT row_format FROM information_schema.partitions"
                            + " WHERE table_schema = DATABASE() AND table_name = ? AND partition_name = ?",
                    String.class, table, partitionName);
            if ("COMPRESSED".equalsIgnoreCase(currentFormat)) {
                continue;
            }
            String partIdent = RevisionLegacyBackfillJob.safeIdent(partitionName);
            jdbcTemplate.execute(
                    "ALTER TABLE " + ident + " REBUILD PARTITION " + partIdent
                            + ", ROW_FORMAT=COMPRESSED");
            log.info("Compressed partition {} on table {}", partitionName, table);
        }
    }

    // ---------------- shared ----------------

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}

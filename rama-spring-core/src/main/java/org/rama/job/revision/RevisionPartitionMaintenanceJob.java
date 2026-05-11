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
 *       {@code revision_archive}. Metadata-only.</li>
 *   <li><b>PostgreSQL:</b> ensure next month's partition exists under
 *       {@code revision_active}, then DETACH every overdue partition
 *       from {@code revision_active} and ATTACH it under
 *       {@code revision_archive}. Metadata-only.</li>
 *   <li>Other DBMS / fallback: log and return — no partition maintenance
 *       applicable.</li>
 * </ul>
 *
 * <p>Schedule monthly (e.g. {@code 0 0 2 1 * ?} — 02:00 on the first day
 * of each month). Recovery from a missed run is automatic — the loop
 * catches up by acting on every overdue partition.
 *
 * <p>JobDataMap keys:
 * <ul>
 *   <li>{@code activeWindowMonths} — int, default 12</li>
 *   <li>{@code activeTableName} — default {@code revision_active}</li>
 *   <li>{@code archiveTableName} — default {@code revision_archive}</li>
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
            case "postgresql"           -> maintainPostgres(context);
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

    // ---------------- PostgreSQL ----------------

    void maintainPostgres(JobExecutionContext context) {
        JobDataMap data = context.getMergedJobDataMap();
        int windowMonths = data.containsKey(KEY_ACTIVE_WINDOW_MONTHS)
                ? data.getIntValue(KEY_ACTIVE_WINDOW_MONTHS) : 12;
        String activeTable = orDefault(data.getString(KEY_ACTIVE_TABLE), "revision_active");
        String archiveTable = orDefault(data.getString(KEY_ARCHIVE_TABLE), "revision_archive");

        ensureNextMonthPartitionPostgres(activeTable);
        detachAndAttachOldPartitionsPostgres(activeTable, archiveTable, windowMonths);
    }

    /**
     * Ensure next month's partition exists under {@code revision_active}.
     * If absent, {@code CREATE TABLE ... PARTITION OF revision_active FOR VALUES FROM ... TO ...}.
     * If present, no-op.
     */
    void ensureNextMonthPartitionPostgres(String activeTable) {
        YearMonth nextMonth = YearMonth.now(ZoneOffset.UTC).plusMonths(1);
        YearMonth monthAfter = nextMonth.plusMonths(1);
        String partitionName = activeTable + "_" + nextMonth.toString().replace("-", "_");
        String lowerBound = nextMonth.atDay(1).toString();
        String upperBound = monthAfter.atDay(1).toString();

        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_class WHERE relname = ?",
                Integer.class, partitionName);
        if (exists != null && exists > 0) {
            log.debug("Postgres partition {} already exists", partitionName);
            return;
        }

        String activeIdent = RevisionLegacyBackfillJob.safeIdent(activeTable);
        String partIdent = RevisionLegacyBackfillJob.safeIdent(partitionName);
        jdbcTemplate.execute(
                "CREATE TABLE " + partIdent + " PARTITION OF " + activeIdent
                        + " FOR VALUES FROM ('" + lowerBound + "') TO ('" + upperBound + "')");
        log.info("Created Postgres partition {} on {} for [{} .. {})",
                partitionName, activeTable, lowerBound, upperBound);
    }

    /**
     * For every partition of {@code revision_active} whose upper bound is
     * older than the active window: DETACH from active, ATTACH to archive
     * with the same bounds. Both operations are metadata-only.
     */
    void detachAndAttachOldPartitionsPostgres(String activeTable, String archiveTable, int windowMonths) {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(windowMonths);

        // pg_inherits + pg_class join surfaces partition children; pg_get_expr returns the bounds.
        List<Map<String, Object>> overdue = jdbcTemplate.queryForList(
                "SELECT c.relname AS partition_name,"
                        + "       pg_get_expr(c.relpartbound, c.oid) AS bounds"
                        + "  FROM pg_inherits i"
                        + "  JOIN pg_class c ON c.oid = i.inhrelid"
                        + "  JOIN pg_class p ON p.oid = i.inhparent"
                        + " WHERE p.relname = ?"
                        + " ORDER BY c.relname ASC",
                activeTable);

        String activeIdent = RevisionLegacyBackfillJob.safeIdent(activeTable);
        String archiveIdent = RevisionLegacyBackfillJob.safeIdent(archiveTable);
        for (Map<String, Object> row : overdue) {
            String partitionName = (String) row.get("partition_name");
            String bounds = (String) row.get("bounds");
            LocalDate upperBound = parsePostgresUpperBound(bounds);
            if (upperBound == null || upperBound.isAfter(cutoff)) {
                continue;
            }

            String partIdent = RevisionLegacyBackfillJob.safeIdent(partitionName);
            jdbcTemplate.execute("ALTER TABLE " + activeIdent + " DETACH PARTITION " + partIdent);
            jdbcTemplate.execute("ALTER TABLE " + archiveIdent + " ATTACH PARTITION " + partIdent
                    + " " + bounds);
            log.info("Detached {} from {} and attached to {} (bounds: {})",
                    partitionName, activeTable, archiveTable, bounds);
        }
    }

    /**
     * Parse the upper bound out of a Postgres partition bounds expression of
     * the form {@code FOR VALUES FROM ('2025-01-01') TO ('2025-02-01')}.
     * Returns null when the format does not match.
     */
    static LocalDate parsePostgresUpperBound(String bounds) {
        if (bounds == null) return null;
        int toIdx = bounds.indexOf(" TO ('");
        if (toIdx < 0) return null;
        int start = toIdx + " TO ('".length();
        int end = bounds.indexOf("'", start);
        if (end < 0) return null;
        try {
            return LocalDate.parse(bounds.substring(start, end));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- shared ----------------

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}

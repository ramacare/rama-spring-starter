package org.rama.job.revision;

import lombok.extern.slf4j.Slf4j;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * One-shot job that copies rows from {@code revision_legacy} into
 * {@code revision_archive} one calendar month per invocation. Insert and
 * delete run inside a single transaction so a legacy row exists in exactly
 * one of the two tables at any moment, keeping the {@code revision_history}
 * view collision-free without {@code UNION DISTINCT}.
 *
 * <p>Idempotent: when the legacy table is absent or empty, the job is a
 * no-op. Safe to re-fire on the same partition because the WHERE clause
 * filters by month, and a successful batch removes its source rows.
 *
 * <p>Schedule with a Quartz cron at a low-traffic hour. Configure the
 * legacy table name via JobDataMap key {@code legacyTableName} (default
 * {@code revision_legacy}).
 */
@Slf4j
public class RevisionLegacyBackfillJob extends QuartzJobBean {

    public static final String KEY_LEGACY_TABLE = "legacyTableName";
    public static final String KEY_ARCHIVE_TABLE = "archiveTableName";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public RevisionLegacyBackfillJob(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        JobDataMap data = context.getMergedJobDataMap();
        String legacy = data.getString(KEY_LEGACY_TABLE);
        String archive = data.getString(KEY_ARCHIVE_TABLE);
        if (legacy == null || legacy.isBlank()) legacy = "revision_legacy";
        if (archive == null || archive.isBlank()) archive = "revision_archive";

        if (!tableExists(legacy)) {
            log.info("Legacy table {} does not exist; backfill is a no-op", legacy);
            return;
        }

        LocalDate earliest = jdbcTemplate.queryForObject(
                "SELECT CAST(MIN(revision_datetime) AS DATE) FROM dbo." + safeIdent(legacy),
                LocalDate.class);
        if (earliest == null) {
            log.info("Legacy table {} is empty; nothing to backfill", legacy);
            return;
        }

        YearMonth month = YearMonth.from(earliest);
        runBatch(legacy, archive, month);
    }

    void runBatch(String legacy, String archive, YearMonth month) {
        final String start = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString();
        final String end = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString();
        final String legacyT = safeIdent(legacy);
        final String archiveT = safeIdent(archive);

        Integer copied = transactionTemplate.execute(status -> {
            String insert = "INSERT INTO dbo." + archiveT + " (id, revision_key, mrn, revision_entity,"
                    + " revision_datetime, revision_data, revision_change,"
                    + " created_by, updated_by, created_at, updated_at)"
                    + " SELECT id, revision_key, mrn, revision_entity, revision_datetime,"
                    + " revision_data, revision_change, created_by, updated_by, created_at, updated_at"
                    + " FROM dbo." + legacyT
                    + " WHERE revision_datetime >= ? AND revision_datetime < ?";
            int inserted = jdbcTemplate.update(insert, start, end);

            int deleted = jdbcTemplate.update(
                    "DELETE FROM dbo." + legacyT + " WHERE revision_datetime >= ? AND revision_datetime < ?",
                    start, end);

            if (inserted != deleted) {
                throw new IllegalStateException(
                        "Backfill mismatch for " + month + ": inserted=" + inserted + " deleted=" + deleted);
            }
            return inserted;
        });

        log.info("Backfilled {} rows for {} from {} -> {}", copied, month, legacy, archive);
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys.tables WHERE name = ?", Integer.class, tableName);
        return count != null && count > 0;
    }

    /**
     * Defensive identifier guard: the only callers pass values from JobDataMap
     * (configured by the deployer, not from user input), but reject anything
     * that isn't a plain SQL identifier so a misconfiguration cannot inject.
     */
    static String safeIdent(String s) {
        if (s == null || !s.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + s);
        }
        return s;
    }
}

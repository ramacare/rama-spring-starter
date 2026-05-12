package org.rama.clickhouse;

import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.quartz.QuartzJobBean;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Reconciles SQL {@code revision} rows into ClickHouse. Catches up after:
 *   - Rows dropped due to queue overflow during a ClickHouse outage
 *   - Schema-init or batch-insert failures that landed rows in the
 *     dead-letter log but not ClickHouse
 *   - Initial bulk import for consumers enabling the feature flag
 *     on an existing SQL {@code revision} history
 *
 * <p>Algorithm:
 *   1. Read high-water mark: {@code SELECT MAX(id) FROM clickhouse.revision} (default 0).
 *   2. Page through {@code SELECT * FROM sql.revision WHERE id > ? ORDER BY id LIMIT 10000}.
 *   3. Enqueue each row into {@link RevisionClickHouseSink} (which handles batching).
 *   4. Repeat until SQL returns fewer than the page size.
 *
 * <p>Idempotent. Cheap when up-to-date (one query against each store).
 * Schedule nightly off-peak. Manual trigger via {@code QuartzService.triggerNow}.
 */
@Slf4j
public class RevisionClickHouseBackfillJob extends QuartzJobBean {

    private static final int PAGE_SIZE = 10_000;

    private final JdbcTemplate sqlJdbcTemplate;
    private final JdbcTemplate clickHouseJdbcTemplate;
    private final RevisionClickHouseSink sink;
    private final String tableName;

    public RevisionClickHouseBackfillJob(JdbcTemplate sqlJdbcTemplate,
                                         JdbcTemplate clickHouseJdbcTemplate,
                                         RevisionClickHouseSink sink,
                                         String tableName) {
        this.sqlJdbcTemplate = sqlJdbcTemplate;
        this.clickHouseJdbcTemplate = clickHouseJdbcTemplate;
        this.sink = sink;
        this.tableName = ClickHouseSchemaInitializer.safeIdent(tableName);
    }

    @Override
    public void executeInternal(JobExecutionContext context) {
        long highWaterMark;
        try {
            Long maxId = clickHouseJdbcTemplate.queryForObject(
                    "SELECT MAX(id) FROM " + tableName, Long.class);
            highWaterMark = maxId == null ? 0L : maxId;
        } catch (RuntimeException e) {
            // ClickHouse unavailable — skip this run; will retry next schedule.
            log.warn("Backfill job: cannot read ClickHouse high-water mark; skipping. Cause: {}",
                    e.getMessage());
            return;
        }
        log.info("Backfill job: ClickHouse high-water id = {}", highWaterMark);

        long cursor = highWaterMark;
        long enqueued = 0;
        while (true) {
            List<ClickHouseRevisionRecord> batch = sqlJdbcTemplate.query(
                    "SELECT id, revision_key, mrn, revision_entity, revision_datetime,"
                            + " revision_data, revision_change,"
                            + " created_by, updated_by, created_at, updated_at"
                            + " FROM revision WHERE id > ? ORDER BY id ASC"
                            + " FETCH FIRST " + PAGE_SIZE + " ROWS ONLY",
                    ROW_MAPPER, cursor);
            if (batch.isEmpty()) break;

            for (ClickHouseRevisionRecord r : batch) {
                sink.offer(r);
                enqueued++;
                cursor = Math.max(cursor, r.id());
            }
            if (batch.size() < PAGE_SIZE) break;
        }
        log.info("Backfill job: enqueued {} rows past id {}", enqueued, highWaterMark);
    }

    private static final RowMapper<ClickHouseRevisionRecord> ROW_MAPPER = (ResultSet rs, int rowNum) ->
            ClickHouseRevisionRecord.of(
                    rs.getLong("id"),
                    rs.getString("revision_key"),
                    rs.getString("mrn"),
                    rs.getString("revision_entity"),
                    toOdt(rs.getTimestamp("revision_datetime")),
                    rs.getString("revision_data"),
                    rs.getString("revision_change"),
                    rs.getString("created_by"),
                    rs.getString("updated_by"),
                    toOdt(rs.getTimestamp("created_at")),
                    toOdt(rs.getTimestamp("updated_at")));

    private static OffsetDateTime toOdt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}

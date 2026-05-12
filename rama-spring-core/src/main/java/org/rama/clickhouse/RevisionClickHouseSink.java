package org.rama.clickhouse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Buffer + bulk-insert sink for revision rows headed to ClickHouse.
 * Thread-safe (synchronized; the volume here is moderate -- one row per
 * revision; not a hot path that justifies lock-free machinery).
 *
 * <p>Two flush triggers:
 *   1. Size: {@link #offer} flushes synchronously when the queue reaches
 *      {@code batchSize}. Single-row ClickHouse inserts are notoriously
 *      slow; batched inserts are its happy path.
 *   2. Time: an external {@code @Scheduled} flusher calls {@link #flush}
 *      every few seconds so low-traffic periods don't strand rows.
 *
 * <p>Back-pressure: at {@code maxQueueSize}, oldest rows are evicted with
 * a warning. Audit data is forever-retention but we'd rather lose a few
 * rows (and log it) than OOM the whole app. The SQL {@code revision} table
 * remains the synchronous source of truth, so a dropped ClickHouse row
 * can be recovered from there by a backfill job.
 *
 * <p>Failure handling:
 *   - <b>Transient</b> (connect/timeout/transient SQL): re-buffer the failed
 *     batch at the front; next flush retries.
 *   - <b>Permanent</b> (bad grammar/schema/data type): log each row at ERROR
 *     for dead-letter visibility and drop the batch. Re-buffering would
 *     create a poison pill that blocks every future batch.
 */
@Slf4j
public class RevisionClickHouseSink {

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;
    private final int batchSize;
    private final int maxQueueSize;
    private final Deque<ClickHouseRevisionRecord> buffer = new ArrayDeque<>();

    public RevisionClickHouseSink(JdbcTemplate jdbcTemplate, String tableName, int batchSize, int maxQueueSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = ClickHouseSchemaInitializer.safeIdent(tableName);
        this.batchSize = batchSize;
        this.maxQueueSize = maxQueueSize;
    }

    /** Enqueue a revision. Flushes synchronously if the buffer reaches batchSize. */
    public synchronized void offer(ClickHouseRevisionRecord record) {
        while (buffer.size() >= maxQueueSize) {
            ClickHouseRevisionRecord dropped = buffer.pollFirst();
            log.warn("RevisionClickHouseSink queue full; dropping oldest record id={}",
                    dropped == null ? "?" : dropped.id());
        }
        buffer.addLast(record);
        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    /** Force-flush whatever's buffered. No-op when empty. */
    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<ClickHouseRevisionRecord> batch = new ArrayList<>(buffer);
        buffer.clear();

        String sql = "INSERT INTO " + tableName + " ("
                + "id, revision_key, mrn, revision_entity, revision_datetime,"
                + " revision_data, revision_change,"
                + " created_by, updated_by, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)";

        try {
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ClickHouseRevisionRecord r = batch.get(i);
                    ps.setLong(1, r.id());
                    ps.setString(2, r.revisionKey());
                    setNullable(ps, 3, r.mrn(), Types.VARCHAR);
                    setNullable(ps, 4, r.revisionEntity(), Types.VARCHAR);
                    ps.setTimestamp(5, Timestamp.from(r.revisionDatetime().toInstant()));
                    ps.setString(6, r.revisionData() == null ? "{}" : r.revisionData());
                    setNullable(ps, 7, r.revisionChange(), Types.VARCHAR);
                    setNullable(ps, 8, r.createdBy(), Types.VARCHAR);
                    setNullable(ps, 9, r.updatedBy(), Types.VARCHAR);
                    setNullableTimestamp(ps, 10, r.createdAt());
                    setNullableTimestamp(ps, 11, r.updatedAt());
                }

                @Override
                public int getBatchSize() {
                    return batch.size();
                }
            });
        } catch (RuntimeException e) {
            if (isTransient(e)) {
                // Network / ClickHouse-down / pool-timeout: re-buffer at the front so a
                // future flush retries. The SQL `revision` table remains source of truth
                // for any rows we eventually drop after queue overflow.
                for (int i = batch.size() - 1; i >= 0; i--) {
                    if (buffer.size() < maxQueueSize) buffer.addFirst(batch.get(i));
                }
                log.warn("ClickHouse batch insert failed (transient); rebuffered {} rows. Cause: {}",
                        batch.size(), rootCauseMessage(e));
            } else {
                // Permanent (schema mismatch, malformed value, etc.): re-buffering would
                // create a poison pill that blocks every future batch. Log to dead-letter
                // and drop. The SQL `revision` rows still exist; backfill job can re-attempt.
                log.error("ClickHouse batch insert failed (PERMANENT — dropping {} rows). Cause: {}",
                        batch.size(), rootCauseMessage(e), e);
                for (ClickHouseRevisionRecord rec : batch) {
                    log.error("DEAD_LETTER revision id={} key={} datetime={}",
                            rec.id(), rec.revisionKey(), rec.revisionDatetime());
                }
            }
        }
    }

    public synchronized int queueSize() {
        return buffer.size();
    }

    /** Test-only helper. */
    synchronized List<Long> peekIds() {
        return buffer.stream().map(ClickHouseRevisionRecord::id).toList();
    }

    /**
     * Distinguish network / availability failures (worth retrying) from
     * data / schema failures (poison pills — must not retry, or they'll
     * block every subsequent batch).
     */
    static boolean isTransient(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof java.sql.SQLTransientException) return true;
            if (cur instanceof java.sql.SQLRecoverableException) return true;
            if (cur instanceof java.net.ConnectException) return true;
            if (cur instanceof java.net.SocketTimeoutException) return true;
            if (cur instanceof java.net.UnknownHostException) return true;
            if (cur instanceof org.springframework.dao.DataAccessResourceFailureException) return true;
            if (cur instanceof org.springframework.dao.TransientDataAccessException) return true;
            if (cur instanceof org.springframework.dao.QueryTimeoutException) return true;
        }
        return false;
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage();
    }

    private static void setNullable(PreparedStatement ps, int index, String value, int sqlType) throws SQLException {
        if (value == null) ps.setNull(index, sqlType);
        else ps.setString(index, value);
    }

    private static void setNullableTimestamp(PreparedStatement ps, int index, OffsetDateTime value) throws SQLException {
        if (value == null) ps.setNull(index, Types.TIMESTAMP);
        else ps.setTimestamp(index, Timestamp.from(value.toInstant()));
    }
}

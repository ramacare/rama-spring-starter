package org.rama.clickhouse;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

public class RevisionClickHouseRepository {

    private static final RowMapper<ClickHouseRevisionRecord> ROW_MAPPER =
            RevisionClickHouseRepository::map;

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;

    public RevisionClickHouseRepository(JdbcTemplate jdbcTemplate, String tableName) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = ClickHouseSchemaInitializer.safeIdent(tableName);
    }

    // Listener fast-path writes carry id=0 because the generated SQL revision id
    // isn't available at @AfterCommit time (the @Async save hasn't returned). Those
    // rows duplicate the canonical id>0 rows that the backfill job ships from SQL.
    // MergeTree doesn't deduplicate, so reads MUST filter out id=0 to avoid the
    // duplicate-row problem. The id=0 rows remain visible to direct diagnostic
    // queries against ClickHouse for ops observability.
    private static final String COLUMNS =
            "id, revision_key, mrn, revision_entity, revision_datetime,"
                    + " revision_data, revision_change,"
                    + " created_by, updated_by, created_at, updated_at";

    /** Latest revision at or before {@code at} for the given key. Empty when no row matches. */
    public Optional<ClickHouseRevisionRecord> getStateAt(String revisionKey, OffsetDateTime at) {
        String sql = "SELECT " + COLUMNS
                + " FROM " + tableName
                + " WHERE id > 0 AND revision_key = ? AND revision_datetime <= ?"
                + " ORDER BY revision_datetime DESC LIMIT 1";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    sql, ROW_MAPPER, revisionKey, Timestamp.from(at.toInstant())));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Full history for one key, newest first. */
    public List<ClickHouseRevisionRecord> findHistory(String revisionKey) {
        String sql = "SELECT " + COLUMNS
                + " FROM " + tableName
                + " WHERE id > 0 AND revision_key = ?"
                + " ORDER BY revision_datetime DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, revisionKey);
    }

    /** All revisions for a patient (mrn) within a time range, newest first. */
    public List<ClickHouseRevisionRecord> findByMrn(String mrn, OffsetDateTime from, OffsetDateTime to) {
        String sql = "SELECT " + COLUMNS
                + " FROM " + tableName
                + " WHERE id > 0 AND mrn = ? AND revision_datetime >= ? AND revision_datetime <= ?"
                + " ORDER BY revision_datetime DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, mrn,
                Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
    }

    private static ClickHouseRevisionRecord map(ResultSet rs, int rowNum) throws SQLException {
        return ClickHouseRevisionRecord.of(
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
    }

    private static OffsetDateTime toOdt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}

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

    private static final String COLUMNS =
            "revision_key, revision_datetime, revision_entity, mrn,"
                    + " revision_data, revision_change,"
                    + " created_by, updated_by, ingested_at";

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;

    public RevisionClickHouseRepository(JdbcTemplate jdbcTemplate, String tableName) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = ClickHouseSchemaInitializer.safeIdent(tableName);
    }

    public Optional<ClickHouseRevisionRecord> getStateAt(String revisionKey, OffsetDateTime at) {
        String sql = "SELECT " + COLUMNS + " FROM " + tableName + " FINAL"
                + " WHERE revision_key = ? AND revision_datetime <= ?"
                + " ORDER BY revision_datetime DESC LIMIT 1";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    sql, ROW_MAPPER, revisionKey, Timestamp.from(at.toInstant())));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ClickHouseRevisionRecord> findHistory(String revisionKey) {
        String sql = "SELECT " + COLUMNS + " FROM " + tableName + " FINAL"
                + " WHERE revision_key = ?"
                + " ORDER BY revision_datetime DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, revisionKey);
    }

    public List<ClickHouseRevisionRecord> findByMrn(String mrn, OffsetDateTime from, OffsetDateTime to) {
        String sql = "SELECT " + COLUMNS + " FROM " + tableName + " FINAL"
                + " WHERE mrn = ? AND revision_datetime >= ? AND revision_datetime <= ?"
                + " ORDER BY revision_datetime DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, mrn,
                Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
    }

    private static ClickHouseRevisionRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new ClickHouseRevisionRecord(
                rs.getString("revision_key"),
                rs.getString("revision_entity"),
                rs.getString("mrn"),
                toOdt(rs.getTimestamp("revision_datetime")),
                rs.getString("revision_data"),
                rs.getString("revision_change"),
                rs.getString("created_by"),
                rs.getString("updated_by"));
        // ingested_at is read but not surfaced — internal to dedup mechanics.
    }

    private static OffsetDateTime toOdt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}

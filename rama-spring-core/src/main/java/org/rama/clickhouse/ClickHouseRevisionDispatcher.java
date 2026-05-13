package org.rama.clickhouse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rama.entity.system.SystemBuffer;
import org.rama.service.system.SystemBufferDispatcher;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Bulk-inserts buffered revision payloads to ClickHouse. Registered as a
 * {@link SystemBufferDispatcher} for buffer_type="revision".
 */
@Slf4j
@RequiredArgsConstructor
public class ClickHouseRevisionDispatcher implements SystemBufferDispatcher {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate clickHouseJdbcTemplate;
    private final String tableName;
    private final ObjectMapper objectMapper;

    @Override
    public String bufferType() { return "revision"; }

    @Override
    public void dispatch(List<SystemBuffer> batch) {
        String safe = ClickHouseSchemaInitializer.safeIdent(tableName);
        String sql = "INSERT INTO " + safe + " ("
                + "revision_key, revision_datetime, revision_entity, mrn,"
                + " revision_data, revision_change, created_by, updated_by)"
                + " VALUES (?,?,?,?,?,?,?,?)";

        clickHouseJdbcTemplate.batchUpdate(sql, batch, batch.size(), (ps, row) -> {
            Map<String, Object> payload = parse(row.getPayload());
            ps.setString(1, str(payload.get("revisionKey")));
            ps.setTimestamp(2, ts(payload.get("revisionDatetime")));
            setNullable(ps, 3, str(payload.get("revisionEntity")));
            setNullable(ps, 4, str(payload.get("mrn")));
            ps.setString(5, jsonOf(payload.get("revisionData")));
            setNullable(ps, 6, jsonOf(payload.get("revisionChange")));
            setNullable(ps, 7, str(payload.get("createdBy")));
            setNullable(ps, 8, str(payload.get("updatedBy")));
        });
    }

    private Map<String, Object> parse(String json) {
        try { return objectMapper.readValue(json, MAP_TYPE); }
        catch (RuntimeException e) { throw new RuntimeException("Bad payload JSON: " + e.getMessage(), e); }
    }

    private String jsonOf(Object val) {
        if (val == null) return null;
        if (val instanceof String s) return s;
        try { return objectMapper.writeValueAsString(val); }
        catch (RuntimeException e) { return String.valueOf(val); }
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }

    private static Timestamp ts(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp t) return t;
        if (v instanceof OffsetDateTime odt) return Timestamp.from(odt.toInstant());
        if (v instanceof String s) return Timestamp.from(OffsetDateTime.parse(s).toInstant());
        throw new IllegalArgumentException("Unsupported timestamp type: " + v.getClass());
    }

    private static void setNullable(PreparedStatement ps, int i, String v) throws SQLException {
        if (v == null) ps.setNull(i, Types.VARCHAR); else ps.setString(i, v);
    }
}

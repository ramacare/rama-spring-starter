package org.rama.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RevisionClickHouseRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final OffsetDateTime now = OffsetDateTime.of(2026, 5, 13, 0, 0, 0, 0, ZoneOffset.UTC);

    private final ClickHouseRevisionRecord sampleRecord = new ClickHouseRevisionRecord(
            "key-1", "Patient", "MRN001", now,
            "{}", null, "user1", "user1");

    @Test
    @SuppressWarnings("unchecked")
    void getStateAt_returnsRecord_whenRowExists() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        when(jdbcTemplate.queryForObject(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(sampleRecord);

        RevisionClickHouseRepository repo =
                new RevisionClickHouseRepository(jdbcTemplate, "revision");
        Optional<ClickHouseRevisionRecord> result = repo.getStateAt("key-1", now);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(sampleRecord);

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM revision FINAL");
        assertThat(sql).contains("WHERE revision_key = ?");
        assertThat(sql).contains("revision_datetime <= ?");
        assertThat(sql).contains("ORDER BY revision_datetime DESC LIMIT 1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStateAt_returnsEmpty_whenNoRow() {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        RevisionClickHouseRepository repo =
                new RevisionClickHouseRepository(jdbcTemplate, "revision");
        Optional<ClickHouseRevisionRecord> result = repo.getStateAt("key-missing", now);

        assertThat(result).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void findHistory_returnsList() {
        List<ClickHouseRevisionRecord> records = List.of(sampleRecord, sampleRecord);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        when(jdbcTemplate.query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(records);

        RevisionClickHouseRepository repo =
                new RevisionClickHouseRepository(jdbcTemplate, "revision");
        List<ClickHouseRevisionRecord> result = repo.findHistory("key-1");

        assertThat(result).hasSize(2);

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM revision FINAL");
        assertThat(sql).contains("WHERE revision_key = ?");
    }
}

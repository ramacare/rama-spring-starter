package org.rama.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RevisionClickHouseRepositoryTest {

    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    void getStateAt_shouldReturnLatestRevisionAtOrBefore() {
        RevisionClickHouseRepository repo = new RevisionClickHouseRepository(jdbcTemplate, "revision");
        OffsetDateTime at = OffsetDateTime.parse("2026-05-01T00:00:00Z");
        ClickHouseRevisionRecord expected = ClickHouseRevisionRecord.of(
                7L, "Patient^id^1", "MRN1", "Patient",
                OffsetDateTime.parse("2026-04-30T08:00:00Z"),
                "{\"name\":\"Jane\"}", null, null, null, null, null);

        when(jdbcTemplate.queryForObject(
                anyString(), any(RowMapper.class), eq("Patient^id^1"), eq(java.sql.Timestamp.from(at.toInstant()))))
                .thenReturn(expected);

        Optional<ClickHouseRevisionRecord> result = repo.getStateAt("Patient^id^1", at);

        assertThat(result).contains(expected);
    }

    @Test
    void getStateAt_shouldReturnEmptyWhenNoRow() {
        RevisionClickHouseRepository repo = new RevisionClickHouseRepository(jdbcTemplate, "revision");
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(), any()))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<ClickHouseRevisionRecord> result = repo.getStateAt(
                "Patient^id^missing", OffsetDateTime.now());

        assertThat(result).isEmpty();
    }

    @Test
    void findHistory_shouldDelegateOrderedQuery() {
        RevisionClickHouseRepository repo = new RevisionClickHouseRepository(jdbcTemplate, "revision");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("Patient^id^1")))
                .thenReturn(List.of());

        List<ClickHouseRevisionRecord> result = repo.findHistory("Patient^id^1");

        assertThat(result).isEmpty();
    }

    @Test
    void allReadQueries_shouldFilterOutListenerPlaceholderRows() {
        // Listener fast-path writes carry id=0 and would otherwise duplicate the canonical
        // id>0 rows shipped by the backfill job. All three read APIs must filter id>0 so
        // duplicates don't leak into reader-facing results.
        RevisionClickHouseRepository repo = new RevisionClickHouseRepository(jdbcTemplate, "revision");
        OffsetDateTime now = OffsetDateTime.now();

        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(null);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of());

        repo.getStateAt("k", now);
        repo.findHistory("k");
        repo.findByMrn("MRN1", now.minusDays(1), now);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), any(RowMapper.class), any(), any());
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any());
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(), any(), any());

        for (String sql : sqlCaptor.getAllValues()) {
            assertThat(sql).contains("id > 0");
        }
    }
}

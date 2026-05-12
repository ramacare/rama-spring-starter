package org.rama.clickhouse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RevisionClickHouseBackfillJobTest {

    @Mock private JdbcTemplate sqlJdbc;
    @Mock private JdbcTemplate clickHouseJdbc;
    @Mock private RevisionClickHouseSink sink;
    @Mock private JobExecutionContext context;

    @Test
    @SuppressWarnings("unchecked")
    void execute_shouldQueueRowsAboveClickHouseHighWaterMark() throws Exception {
        when(clickHouseJdbc.queryForObject("SELECT MAX(id) FROM revision", Long.class)).thenReturn(1000L);
        when(sqlJdbc.query(anyString(), any(RowMapper.class), eq(1000L)))
                .thenReturn(List.of(
                        sampleRecord(1001L), sampleRecord(1002L), sampleRecord(1003L)));

        RevisionClickHouseBackfillJob job = new RevisionClickHouseBackfillJob(
                sqlJdbc, clickHouseJdbc, sink, "revision");
        job.executeInternal(context);

        verify(sink).offer(argMatchesId(1001L));
        verify(sink).offer(argMatchesId(1002L));
        verify(sink).offer(argMatchesId(1003L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_shouldStartFromZero_whenClickHouseEmpty() throws Exception {
        when(clickHouseJdbc.queryForObject("SELECT MAX(id) FROM revision", Long.class)).thenReturn(null);
        when(sqlJdbc.query(anyString(), any(RowMapper.class), eq(0L))).thenReturn(List.of());

        new RevisionClickHouseBackfillJob(sqlJdbc, clickHouseJdbc, sink, "revision")
                .executeInternal(context);

        verify(sink, never()).offer(any());
    }

    @Test
    void execute_shouldSwallowClickHouseHighWaterFailure_andSkipRun() throws Exception {
        when(clickHouseJdbc.queryForObject("SELECT MAX(id) FROM revision", Long.class))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                        "ClickHouse down"));

        // Must not throw; just no-op until ClickHouse recovers.
        new RevisionClickHouseBackfillJob(sqlJdbc, clickHouseJdbc, sink, "revision")
                .executeInternal(context);

        verify(sink, never()).offer(any());
    }

    private static ClickHouseRevisionRecord sampleRecord(long id) {
        return ClickHouseRevisionRecord.of(
                id, "Entity^id^" + id, null, "Entity",
                OffsetDateTime.now(), "{}", null, null, null, null, null);
    }

    private static ClickHouseRevisionRecord argMatchesId(long id) {
        return org.mockito.ArgumentMatchers.argThat(r -> r != null && r.id() == id);
    }
}

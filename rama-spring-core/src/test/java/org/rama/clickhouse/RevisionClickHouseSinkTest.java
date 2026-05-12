package org.rama.clickhouse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RevisionClickHouseSinkTest {

    @Mock private JdbcTemplate jdbcTemplate;
    private RevisionClickHouseSink sink;

    @BeforeEach
    void setUp() {
        sink = new RevisionClickHouseSink(jdbcTemplate, "revision", /* batchSize */ 3, /* maxQueueSize */ 1000);
    }

    @Test
    void offer_shouldNotFlush_belowBatchSize() {
        sink.offer(record(1L));
        sink.offer(record(2L));

        verify(jdbcTemplate, never()).batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class));
        assertThat(sink.queueSize()).isEqualTo(2);
    }

    @Test
    void offer_shouldFlushWhenBatchSizeReached() {
        sink.offer(record(1L));
        sink.offer(record(2L));
        sink.offer(record(3L));

        verify(jdbcTemplate).batchUpdate(
                startsWith("INSERT INTO revision"),
                any(BatchPreparedStatementSetter.class));
        assertThat(sink.queueSize()).isZero();
    }

    @Test
    void flush_shouldNoopWhenEmpty() {
        sink.flush();
        verify(jdbcTemplate, never()).batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class));
    }

    @Test
    void flush_shouldEmitBatchInsertWithAllBufferedRows() {
        sink.offer(record(1L));
        sink.offer(record(2L));
        sink.flush();

        verify(jdbcTemplate).batchUpdate(
                startsWith("INSERT INTO revision"),
                any(BatchPreparedStatementSetter.class));
        assertThat(sink.queueSize()).isZero();
    }

    @Test
    void offer_shouldDropOldestWhenQueueFull() {
        RevisionClickHouseSink tightSink =
                new RevisionClickHouseSink(jdbcTemplate, "revision", 1000, /* maxQueueSize */ 2);

        tightSink.offer(record(1L));
        tightSink.offer(record(2L));
        tightSink.offer(record(3L));  // should evict id=1

        assertThat(tightSink.queueSize()).isEqualTo(2);
        assertThat(tightSink.peekIds()).containsExactly(2L, 3L);
    }

    @Test
    void flush_shouldRebufferOnTransientFailure() {
        sink.offer(record(1L));
        sink.offer(record(2L));

        // Simulate ClickHouse down / network error (transient).
        when(jdbcTemplate.batchUpdate(startsWith("INSERT INTO revision"), any(BatchPreparedStatementSetter.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                        "Connection refused",
                        new java.sql.SQLTransientConnectionException("Connection refused")));

        sink.flush();

        // Rows should be re-buffered for the next flush attempt.
        assertThat(sink.queueSize()).isEqualTo(2);
        assertThat(sink.peekIds()).containsExactly(1L, 2L);
    }

    @Test
    void flush_shouldDropPoisonPillOnPermanentFailure() {
        sink.offer(record(1L));
        sink.offer(record(2L));

        // Simulate a permanent schema/data error (poison pill).
        when(jdbcTemplate.batchUpdate(startsWith("INSERT INTO revision"), any(BatchPreparedStatementSetter.class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException(
                        "INSERT", "INSERT INTO revision",
                        new java.sql.SQLSyntaxErrorException("Type mismatch in column revision_data")));

        sink.flush();

        // Permanent failures must NOT be re-buffered -- they'd block every future batch.
        assertThat(sink.queueSize()).isZero();
    }

    private static ClickHouseRevisionRecord record(long id) {
        return ClickHouseRevisionRecord.of(
                id, "Entity^id^" + id, null, "Entity",
                OffsetDateTime.now(), "{}", null, null, null, null, null);
    }
}

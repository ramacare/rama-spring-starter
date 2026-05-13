package org.rama.clickhouse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.JsonConverter;
import org.rama.entity.system.SystemBuffer;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ClickHouseRevisionDispatcherTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;
    private ClickHouseRevisionDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        objectMapper = JsonConverter.createObjectMapper();
        dispatcher = new ClickHouseRevisionDispatcher(jdbcTemplate, "revision", objectMapper);
    }

    @Test
    void bufferType_isRevision() {
        assertThat(dispatcher.bufferType()).isEqualTo("revision");
    }

    @Test
    void dispatch_buildsInsertSqlAndBindsRowValues() {
        // Arrange
        SystemBuffer buf = new SystemBuffer();
        buf.setPayload("{\"revisionKey\":\"k1\",\"revisionDatetime\":\"2026-01-01T00:00:00Z\","
                + "\"revisionEntity\":\"Patient\",\"mrn\":\"M1\","
                + "\"revisionData\":{\"name\":\"Alice\"},\"revisionChange\":null,"
                + "\"createdBy\":\"u1\",\"updatedBy\":null}");

        when(jdbcTemplate.batchUpdate(any(String.class), anyList(), anyInt(), any()))
                .thenReturn(new int[][]{{1}});

        // Act
        dispatcher.dispatch(List.of(buf));

        // Assert - verify batchUpdate was called with the correct INSERT SQL
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), anyList(), anyInt(), any());

        String sql = sqlCaptor.getValue();
        assertThat(sql).containsIgnoringCase("INSERT INTO revision");
        assertThat(sql).contains("revision_key");
        assertThat(sql).contains("revision_datetime");
        assertThat(sql).contains("VALUES (?,?,?,?,?,?,?,?)");
    }
}

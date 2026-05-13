package org.rama.job.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.rama.entity.system.SystemBuffer;
import org.rama.repository.system.SystemBufferRepository;
import org.rama.service.system.SystemBufferDispatcher;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class SystemBufferDrainJobTest {

    @Mock
    private SystemBufferRepository repository;

    @Mock
    private SystemBufferDispatcher dispatcher;

    private SystemBuffer makeRow(long id) {
        SystemBuffer row = new SystemBuffer();
        row.setBufferType("revision");
        row.setPayload("{}");
        row.setAttemptCount(0);
        return row;
    }

    @Test
    void drainOne_emptyBuffer_doesNothing() {
        when(repository.findByBufferTypeOrderByIdAsc(eq("revision"), any()))
                .thenReturn(List.of());

        SystemBufferDrainJob job = new SystemBufferDrainJob(repository, List.of(dispatcher));
        job.drainOne("revision", dispatcher, 1000);

        verify(dispatcher, never()).dispatch(any());
        verify(repository, never()).deleteAllInBatch(any());
    }

    @Test
    void drainOne_nonEmpty_dispatchesAndDeletes() {
        List<SystemBuffer> batch = List.of(makeRow(1), makeRow(2), makeRow(3));
        when(repository.findByBufferTypeOrderByIdAsc(eq("revision"), any()))
                .thenReturn(batch);

        SystemBufferDrainJob job = new SystemBufferDrainJob(repository, List.of(dispatcher));
        job.drainOne("revision", dispatcher, 1000);

        verify(dispatcher).dispatch(batch);
        verify(repository).deleteAllInBatch(batch);
    }

    @Test
    void drainOne_dispatcherThrows_noDeleteUpdatesAttemptCount() {
        List<SystemBuffer> batch = List.of(makeRow(1), makeRow(2));
        when(repository.findByBufferTypeOrderByIdAsc(eq("revision"), any()))
                .thenReturn(batch);
        doThrow(new RuntimeException("connection refused")).when(dispatcher).dispatch(any());

        SystemBufferDrainJob job = new SystemBufferDrainJob(repository, List.of(dispatcher));
        job.drainOne("revision", dispatcher, 1000);

        verify(repository, never()).deleteAllInBatch(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SystemBuffer>> saveCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saveCaptor.capture());

        List<SystemBuffer> saved = saveCaptor.getValue();
        assertThat(saved).allSatisfy(row -> {
            assertThat(row.getAttemptCount()).isEqualTo(1);
            assertThat(row.getLastError()).isEqualTo("connection refused");
            assertThat(row.getLastAttemptAt()).isNotNull();
        });
    }

    @Test
    void executeInternal_multipleBufferTypes_callsEachDispatcher() {
        SystemBufferDispatcher revisionDispatcher = mock(SystemBufferDispatcher.class);
        when(revisionDispatcher.bufferType()).thenReturn("revision");

        SystemBufferDispatcher auditDispatcher = mock(SystemBufferDispatcher.class);
        when(auditDispatcher.bufferType()).thenReturn("audit");

        SystemBuffer revRow = new SystemBuffer();
        revRow.setBufferType("revision");
        revRow.setPayload("{}");

        SystemBuffer auditRow = new SystemBuffer();
        auditRow.setBufferType("audit");
        auditRow.setPayload("{}");

        when(repository.findByBufferTypeOrderByIdAsc(eq("revision"), eq(PageRequest.of(0, 1000))))
                .thenReturn(List.of(revRow));
        when(repository.findByBufferTypeOrderByIdAsc(eq("audit"), eq(PageRequest.of(0, 1000))))
                .thenReturn(List.of(auditRow));

        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());

        SystemBufferDrainJob job = new SystemBufferDrainJob(repository, List.of(revisionDispatcher, auditDispatcher));
        job.executeInternal(context);

        verify(revisionDispatcher).dispatch(List.of(revRow));
        verify(auditDispatcher).dispatch(List.of(auditRow));
    }
}

package org.rama.service.system;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.system.SystemBuffer;
import org.rama.repository.system.SystemBufferRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class SystemBufferServiceTest {

    @Mock
    private SystemBufferRepository repository;

    private SystemBufferService service;

    @Captor
    private ArgumentCaptor<SystemBuffer> captor;

    @BeforeEach
    void setUp() {
        service = new SystemBufferService(repository);
    }

    @Test
    void enqueue_savesRowWithProvidedFields() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.enqueue("revision", "{\"id\":1}", "clickhouse:revision");

        verify(repository).save(captor.capture());
        SystemBuffer saved = captor.getValue();
        assertThat(saved.getBufferType()).isEqualTo("revision");
        assertThat(saved.getPayload()).isEqualTo("{\"id\":1}");
        assertThat(saved.getTarget()).isEqualTo("clickhouse:revision");
    }

    @Test
    void enqueue_returnsSavedEntity() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SystemBuffer result = service.enqueue("revision", "{\"id\":1}", "clickhouse:revision");

        assertThat(result).isNotNull();
        assertThat(result.getBufferType()).isEqualTo("revision");
    }
}

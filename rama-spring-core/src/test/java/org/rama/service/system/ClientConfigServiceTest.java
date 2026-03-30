package org.rama.service.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.system.ClientConfig;
import org.rama.entity.system.SystemLog;
import org.rama.repository.system.ClientConfigRepository;
import org.rama.repository.system.SystemLogRepository;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientConfigServiceTest {

    @Mock
    private ClientConfigRepository clientConfigRepository;

    @Mock
    private SystemLogRepository systemLogRepository;

    @InjectMocks
    private ClientConfigService clientConfigService;

    @Captor
    private ArgumentCaptor<ClientConfig> clientConfigCaptor;

    @Captor
    private ArgumentCaptor<SystemLog> systemLogCaptor;

    @Test
    void retrieveOrRegister_shouldReturnExistingByComputerName() {
        ClientConfig existing = new ClientConfig();
        existing.setId(1L);
        existing.setComputerName("PC-001");
        existing.setFingerprint("fp-abc");

        when(clientConfigRepository.findByComputerName("PC-001")).thenReturn(Optional.of(existing));
        when(clientConfigRepository.save(any(ClientConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ClientConfig result = clientConfigService.retrieveOrRegister("PC-001", "fp-abc");

        assertThat(result.getComputerName()).isEqualTo("PC-001");
        assertThat(result.getLastSeenDatetime()).isNotNull();
        verify(clientConfigRepository).findByComputerName("PC-001");
        verify(clientConfigRepository).save(any(ClientConfig.class));
    }

    @Test
    void retrieveOrRegister_shouldCreateNewWhenNotFound() {
        when(clientConfigRepository.findByComputerName("NEW-PC")).thenReturn(Optional.empty());
        when(clientConfigRepository.findByFingerprint("fp-new")).thenReturn(Collections.emptyList());
        when(clientConfigRepository.save(any(ClientConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ClientConfig result = clientConfigService.retrieveOrRegister("NEW-PC", "fp-new");

        verify(clientConfigRepository).save(clientConfigCaptor.capture());
        ClientConfig saved = clientConfigCaptor.getValue();
        assertThat(saved.getComputerName()).isEqualTo("NEW-PC");
        assertThat(saved.getFingerprint()).isEqualTo("fp-new");
        assertThat(saved.getLastSeenDatetime()).isNotNull();
        assertThat(saved.getConfiguration()).isNotNull();
    }

    @Test
    void retrieveOrRegister_shouldHandleFingerprintChange() {
        ClientConfig existing = new ClientConfig();
        existing.setId(1L);
        existing.setComputerName("PC-001");
        existing.setFingerprint("old-fp");

        when(clientConfigRepository.findByComputerName("PC-001")).thenReturn(Optional.of(existing));
        when(clientConfigRepository.save(any(ClientConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ClientConfig result = clientConfigService.retrieveOrRegister("PC-001", "new-fp");

        assertThat(result.getFingerprint()).isEqualTo("new-fp");
        verify(systemLogRepository).save(systemLogCaptor.capture());
        SystemLog log = systemLogCaptor.getValue();
        assertThat(log.getLogLevel()).isEqualTo(SystemLog.LogLevel.WARN);
        assertThat(log.getLogKey()).isEqualTo("CLIENT_FINGERPRINT_CHANGED");
    }

    @Test
    void retrieveOrRegister_shouldHandleDuplicateFingerprint() {
        when(clientConfigRepository.findByComputerName("PC-X")).thenReturn(Optional.empty());

        ClientConfig dup1 = new ClientConfig();
        dup1.setId(1L);
        ClientConfig dup2 = new ClientConfig();
        dup2.setId(2L);
        when(clientConfigRepository.findByFingerprint("dup-fp")).thenReturn(List.of(dup1, dup2));

        assertThatThrownBy(() -> clientConfigService.retrieveOrRegister("PC-X", "dup-fp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate fingerprint");

        verify(systemLogRepository).save(systemLogCaptor.capture());
        assertThat(systemLogCaptor.getValue().getLogLevel()).isEqualTo(SystemLog.LogLevel.ERROR);
    }

    @Test
    void retrieveOrRegister_shouldThrowWhenComputerNameBlank() {
        assertThatThrownBy(() -> clientConfigService.retrieveOrRegister("", "fp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("computerName");
    }

    @Test
    void retrieveOrRegister_shouldThrowWhenFingerprintNull() {
        assertThatThrownBy(() -> clientConfigService.retrieveOrRegister("PC-001", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
    }

    @Test
    void retrieveOrRegister_shouldReturnExistingBySingleFingerprint() {
        ClientConfig existing = new ClientConfig();
        existing.setId(5L);
        existing.setComputerName("OLD-PC");
        existing.setFingerprint("unique-fp");

        when(clientConfigRepository.findByComputerName("NEW-PC")).thenReturn(Optional.empty());
        when(clientConfigRepository.findByFingerprint("unique-fp")).thenReturn(List.of(existing));
        when(clientConfigRepository.save(any(ClientConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ClientConfig result = clientConfigService.retrieveOrRegister("NEW-PC", "unique-fp");

        assertThat(result.getId()).isEqualTo(5L);
        assertThat(result.getLastSeenDatetime()).isNotNull();
    }
}

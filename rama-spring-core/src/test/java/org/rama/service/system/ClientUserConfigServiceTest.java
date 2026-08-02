package org.rama.service.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.system.ClientUserConfig;
import org.rama.repository.system.ClientUserConfigRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientUserConfigServiceTest {

    @Mock
    private ClientUserConfigRepository clientUserConfigRepository;

    @InjectMocks
    private ClientUserConfigService clientUserConfigService;

    @Captor
    private ArgumentCaptor<ClientUserConfig> clientUserConfigCaptor;

    @Test
    void retrieveOrRegister_shouldReturnExistingByClientUsername() {
        ClientUserConfig existing = new ClientUserConfig();
        existing.setId(1L);
        existing.setClientUsername("RAMA\\somchai");

        when(clientUserConfigRepository.findByClientUsername("RAMA\\somchai")).thenReturn(Optional.of(existing));
        when(clientUserConfigRepository.save(any(ClientUserConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ClientUserConfig result = clientUserConfigService.retrieveOrRegister("RAMA\\somchai");

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getLastSeenDatetime()).isNotNull();
        verify(clientUserConfigRepository).save(any(ClientUserConfig.class));
    }

    @Test
    void retrieveOrRegister_shouldCreateNewWhenNotFound() {
        when(clientUserConfigRepository.findByClientUsername("RAMA\\newuser")).thenReturn(Optional.empty());
        when(clientUserConfigRepository.save(any(ClientUserConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        clientUserConfigService.retrieveOrRegister("RAMA\\newuser");

        verify(clientUserConfigRepository).save(clientUserConfigCaptor.capture());
        ClientUserConfig saved = clientUserConfigCaptor.getValue();
        assertThat(saved.getClientUsername()).isEqualTo("RAMA\\newuser");
        assertThat(saved.getLastSeenDatetime()).isNotNull();
        assertThat(saved.getConfiguration()).isNotNull().isEmpty();
    }

    @Test
    void retrieveOrRegister_shouldThrowWhenClientUsernameBlank() {
        assertThatThrownBy(() -> clientUserConfigService.retrieveOrRegister("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientUsername");

        verify(clientUserConfigRepository, never()).save(any());
    }

    @Test
    void retrieveOrRegister_shouldThrowWhenClientUsernameNull() {
        assertThatThrownBy(() -> clientUserConfigService.retrieveOrRegister(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientUsername");

        verify(clientUserConfigRepository, never()).save(any());
    }
}

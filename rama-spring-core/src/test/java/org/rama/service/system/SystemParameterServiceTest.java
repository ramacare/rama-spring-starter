package org.rama.service.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.system.SystemParameter;
import org.rama.repository.system.SystemParameterRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemParameterServiceTest {

    @Mock
    private SystemParameterRepository systemParameterRepository;

    @InjectMocks
    private SystemParameterService systemParameterService;

    @Captor
    private ArgumentCaptor<SystemParameter> parameterCaptor;

    @Test
    void getParameter_shouldReturnValueWhenExists() {
        SystemParameter param = new SystemParameter();
        param.setParameterKey("app.mode");
        param.setParameterValue("production");

        when(systemParameterRepository.findSystemParameterByParameterKey("app.mode")).thenReturn(param);

        String result = systemParameterService.getParameter("app.mode");

        assertThat(result).isEqualTo("production");
    }

    @Test
    void getParameter_shouldReturnNullWhenNotExists() {
        when(systemParameterRepository.findSystemParameterByParameterKey("missing.key")).thenReturn(null);

        String result = systemParameterService.getParameter("missing.key");

        assertThat(result).isNull();
    }

    @Test
    void setParameter_shouldCreateWhenNotExists() {
        when(systemParameterRepository.findByParameterKeyForUpdate("new.key")).thenReturn(Optional.empty());
        when(systemParameterRepository.save(any(SystemParameter.class))).thenAnswer(inv -> inv.getArgument(0));

        systemParameterService.setParameter("new.key", "new-value");

        verify(systemParameterRepository).save(parameterCaptor.capture());
        SystemParameter saved = parameterCaptor.getValue();
        assertThat(saved.getParameterKey()).isEqualTo("new.key");
        assertThat(saved.getParameterValue()).isEqualTo("new-value");
    }

    @Test
    void setParameter_shouldUpdateWhenExists() {
        SystemParameter existing = new SystemParameter();
        existing.setParameterKey("existing.key");
        existing.setParameterValue("old-value");

        when(systemParameterRepository.findByParameterKeyForUpdate("existing.key")).thenReturn(Optional.of(existing));
        when(systemParameterRepository.save(any(SystemParameter.class))).thenAnswer(inv -> inv.getArgument(0));

        systemParameterService.setParameter("existing.key", "updated-value");

        verify(systemParameterRepository).save(parameterCaptor.capture());
        SystemParameter saved = parameterCaptor.getValue();
        assertThat(saved.getParameterKey()).isEqualTo("existing.key");
        assertThat(saved.getParameterValue()).isEqualTo("updated-value");
    }
}

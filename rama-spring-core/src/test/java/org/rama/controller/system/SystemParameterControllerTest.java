package org.rama.controller.system;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.rama.entity.system.SystemParameter;
import org.rama.repository.system.SystemParameterRepository;
import org.rama.service.GenericEntityService;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemParameterControllerTest {

    @Mock
    private SystemParameterRepository systemParameterRepository;

    private SystemParameterController systemParameterController;

    @BeforeEach
    void setup() {
        GenericEntityService genericEntityService =
                new GenericEntityService(JsonMapper.builder().build());
        systemParameterController =
                new SystemParameterController(genericEntityService, systemParameterRepository);
    }

    @Test
    void deleteSystemParameter_whenExists_shouldHardDeleteRow() {
        SystemParameter sp = new SystemParameter();
        sp.setParameterKey("PRINT_TIMEOUT");
        sp.setParameterValue("30");
        when(systemParameterRepository.findById("PRINT_TIMEOUT")).thenReturn(Optional.of(sp));

        Optional<SystemParameter> result =
                systemParameterController.deleteEntity(Map.of("parameterKey", "PRINT_TIMEOUT"));

        assertThat(result).isPresent();
        assertThat(result.get().getParameterKey()).isEqualTo("PRINT_TIMEOUT");
        verify(systemParameterRepository).delete(sp);
    }
}

package org.rama.service;

import graphql.GraphQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.rama.entity.StatusCode;
import org.rama.entity.master.MasterItem;
import org.rama.repository.BaseRepository;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GenericEntityServiceTest {

    @Mock
    private BaseRepository<MasterItem, String> masterItemRepository;

    @Captor
    private ArgumentCaptor<MasterItem> entityCaptor;

    @BeforeEach
    void setup() {
        // Initialize the static JsonMapper used by GenericEntityService
        JsonMapper mapper = JsonMapper.builder().build();
        new GenericEntityService(mapper);
    }

    @Test
    void createEntity_shouldCreateAndSave() {
        // Arrange
        Map<String, Object> input = new HashMap<>();
        input.put("id", "GENDER^M");
        input.put("groupKey", "GENDER");
        input.put("itemCode", "M");
        input.put("itemValue", "Male");

        when(masterItemRepository.existsById("GENDER^M")).thenReturn(false);
        when(masterItemRepository.save(any(MasterItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(masterItemRepository).flush();
        doNothing().when(masterItemRepository).refresh(any(MasterItem.class));

        // Act
        Optional<MasterItem> result = GenericEntityService.createEntity(
                MasterItem.class, masterItemRepository, input, "id"
        );

        // Assert
        assertThat(result).isPresent();
        verify(masterItemRepository).save(any(MasterItem.class));
        verify(masterItemRepository).flush();
        verify(masterItemRepository).refresh(any(MasterItem.class));
    }

    @Test
    void createEntity_shouldThrow_whenDuplicateKey() {
        // Arrange
        Map<String, Object> input = new HashMap<>();
        input.put("id", "GENDER^M");

        when(masterItemRepository.existsById("GENDER^M")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() ->
                GenericEntityService.createEntity(MasterItem.class, masterItemRepository, input, "id")
        ).isInstanceOf(GraphQLException.class);
        verify(masterItemRepository, never()).save(any());
    }

    @Test
    void updateEntity_shouldMergeFieldsAndSave() {
        // Arrange
        MasterItem existing = new MasterItem("GENDER", "M", "Male");
        existing.setId("GENDER^M");

        Map<String, Object> input = new HashMap<>();
        input.put("id", "GENDER^M");
        input.put("itemValue", "Male Updated");

        when(masterItemRepository.findById("GENDER^M")).thenReturn(Optional.of(existing));
        when(masterItemRepository.save(any(MasterItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(masterItemRepository).flush();
        doNothing().when(masterItemRepository).refresh(any(MasterItem.class));

        // Act
        Optional<MasterItem> result = GenericEntityService.updateEntity(
                MasterItem.class, masterItemRepository, input, "id"
        );

        // Assert
        assertThat(result).isPresent();
        verify(masterItemRepository).findById("GENDER^M");
        verify(masterItemRepository).save(any(MasterItem.class));
    }

    @Test
    void updateEntity_shouldReturnEmpty_whenEntityNotFound() {
        // Arrange
        Map<String, Object> input = new HashMap<>();
        input.put("id", "NONEXISTENT");

        when(masterItemRepository.findById("NONEXISTENT")).thenReturn(Optional.empty());

        // Act
        Optional<MasterItem> result = GenericEntityService.updateEntity(
                MasterItem.class, masterItemRepository, input, "id"
        );

        // Assert
        assertThat(result).isEmpty();
        verify(masterItemRepository, never()).save(any());
    }

    @Test
    void deleteEntity_shouldSetStatusTerminated() {
        // Arrange
        MasterItem existing = new MasterItem("GENDER", "M", "Male");
        existing.setId("GENDER^M");
        existing.setStatusCode(StatusCode.active);

        Map<String, Object> input = new HashMap<>();
        input.put("id", "GENDER^M");

        when(masterItemRepository.findById("GENDER^M")).thenReturn(Optional.of(existing));
        when(masterItemRepository.save(any(MasterItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Optional<MasterItem> result = GenericEntityService.softDeleteEntity(
                MasterItem.class, masterItemRepository, input, "id"
        );

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().getStatusCode()).isEqualTo(StatusCode.terminated);
        verify(masterItemRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getStatusCode()).isEqualTo(StatusCode.terminated);
    }

    @Test
    void deleteEntity_shouldThrow_whenEntityNotFound() {
        // Arrange
        Map<String, Object> input = new HashMap<>();
        input.put("id", "NONEXISTENT");

        when(masterItemRepository.findById("NONEXISTENT")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() ->
                GenericEntityService.softDeleteEntity(MasterItem.class, masterItemRepository, input, "id")
        ).isInstanceOf(GraphQLException.class)
                .hasMessageContaining("Entity not found");
    }

    @Test
    void createEntity_shouldUnwrapInputKey() {
        // Arrange - input wrapped in "input" key
        Map<String, Object> innerInput = new HashMap<>();
        innerInput.put("id", "GENDER^M");
        innerInput.put("groupKey", "GENDER");
        innerInput.put("itemCode", "M");
        innerInput.put("itemValue", "Male");

        Map<String, Object> wrappedInput = new HashMap<>();
        wrappedInput.put("input", innerInput);

        when(masterItemRepository.existsById("GENDER^M")).thenReturn(false);
        when(masterItemRepository.save(any(MasterItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(masterItemRepository).flush();
        doNothing().when(masterItemRepository).refresh(any(MasterItem.class));

        // Act
        Optional<MasterItem> result = GenericEntityService.createEntity(
                MasterItem.class, masterItemRepository, wrappedInput, "id"
        );

        // Assert
        assertThat(result).isPresent();
        verify(masterItemRepository).save(any(MasterItem.class));
    }
}

package org.rama.controller.master;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.rama.entity.master.MasterItem;
import org.rama.repository.master.MasterItemRepository;
import org.rama.service.GenericEntityService;
import org.rama.service.GenericMongoService;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MasterItemControllerTest {

    @Mock
    private MasterItemRepository masterItemRepository;

    @Mock
    private JPAQueryFactory queryFactory;

    @Mock
    private ObjectProvider<GenericMongoService> genericMongoServiceProvider;

    private MasterItemController masterItemController;

    @BeforeEach
    void setup() {
        GenericEntityService genericEntityService = new GenericEntityService(JsonMapper.builder().build());
        masterItemController = new MasterItemController(genericEntityService, masterItemRepository, queryFactory, genericMongoServiceProvider);
    }

    @Test
    void masterItemByGroupKey_shouldReturnSortedItems() {
        // Arrange
        MasterItem item1 = new MasterItem("GENDER", "M", "Male");
        item1.setId("GENDER^M");
        item1.setOrdering(1);

        MasterItem item2 = new MasterItem("GENDER", "F", "Female");
        item2.setId("GENDER^F");
        item2.setOrdering(2);

        when(masterItemRepository.findMasterItemByGroupKeyOrderByOrderingAndItemCodeAsc("GENDER"))
                .thenReturn(List.of(item1, item2));

        // Act
        List<MasterItem> result = masterItemController.masterItemByGroupKey("GENDER");

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getItemCode()).isEqualTo("M");
        assertThat(result.get(1).getItemCode()).isEqualTo("F");
        verify(masterItemRepository).findMasterItemByGroupKeyOrderByOrderingAndItemCodeAsc("GENDER");
    }

    @Test
    void masterItemByGroupKeyAndItemCode_shouldReturnMatchingItem() {
        // Arrange
        MasterItem item = new MasterItem("GENDER", "M", "Male");
        item.setId("GENDER^M");
        when(masterItemRepository.findMasterItemByGroupKeyAndItemCode("GENDER", "M"))
                .thenReturn(Optional.of(item));

        // Act
        Optional<MasterItem> result = masterItemController.masterItemByGroupKeyAndItemCode("GENDER", "M");

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().getGroupKey()).isEqualTo("GENDER");
        assertThat(result.get().getItemCode()).isEqualTo("M");
        assertThat(result.get().getItemValue()).isEqualTo("Male");
        verify(masterItemRepository).findMasterItemByGroupKeyAndItemCode("GENDER", "M");
    }

    @Test
    void masterItemByGroupKeyAndItemCode_shouldReturnEmpty_whenNotFound() {
        // Arrange
        when(masterItemRepository.findMasterItemByGroupKeyAndItemCode("GENDER", "X"))
                .thenReturn(Optional.empty());

        // Act
        Optional<MasterItem> result = masterItemController.masterItemByGroupKeyAndItemCode("GENDER", "X");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void masterItemByGroupKeyAndItemCodeIn_shouldReturnFilteredItems() {
        // Arrange
        MasterItem item1 = new MasterItem("GENDER", "M", "Male");
        item1.setId("GENDER^M");
        MasterItem item2 = new MasterItem("GENDER", "F", "Female");
        item2.setId("GENDER^F");

        when(masterItemRepository.findMasterItemByGroupKeyAndItemCodeIn("GENDER", List.of("M", "F")))
                .thenReturn(List.of(item1, item2));

        // Act
        List<MasterItem> result = masterItemController.masterItemByGroupKeyAndItemCodeIn("GENDER", List.of("M", "F"));

        // Assert
        assertThat(result).hasSize(2);
        verify(masterItemRepository).findMasterItemByGroupKeyAndItemCodeIn("GENDER", List.of("M", "F"));
    }

    @Test
    void masterItemByGroupKeyAndItemCodeIn_shouldReturnAllByGroupKey_whenItemCodesNull() {
        // Arrange
        MasterItem item = new MasterItem("GENDER", "M", "Male");
        item.setId("GENDER^M");
        when(masterItemRepository.findMasterItemByGroupKey("GENDER"))
                .thenReturn(List.of(item));

        // Act
        List<MasterItem> result = masterItemController.masterItemByGroupKeyAndItemCodeIn("GENDER", null);

        // Assert
        assertThat(result).hasSize(1);
        verify(masterItemRepository).findMasterItemByGroupKey("GENDER");
    }
}

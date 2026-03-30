package org.rama.service;

import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.Type;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.Revision;
import org.rama.repository.revision.RevisionRepository;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RevisionServiceTest {

    @Mock
    private RevisionRepository revisionRepository;

    @InjectMocks
    private RevisionService revisionService;

    @Captor
    private ArgumentCaptor<Revision> revisionCaptor;

    @Test
    void saveRevision_shouldPersistWithCorrectFields() {
        // Arrange
        String revisionKey = "org.rama.entity.Patient^id^12345";
        String revisionEntity = "Patient";
        Map<String, Object> revisionData = new HashMap<>();
        revisionData.put("name", "John");
        Map<String, Object> revisionChange = new HashMap<>();
        revisionChange.put("name", "Jane");

        when(revisionRepository.save(any(Revision.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        revisionService.saveRevision(revisionKey, revisionEntity, revisionData, revisionChange);

        // Assert
        verify(revisionRepository).save(revisionCaptor.capture());
        Revision saved = revisionCaptor.getValue();
        assertThat(saved.getRevisionKey()).isEqualTo(revisionKey);
        assertThat(saved.getRevisionEntity()).isEqualTo(revisionEntity);
        assertThat(saved.getRevisionData()).isEqualTo(revisionData);
        assertThat(saved.getRevisionChange()).isEqualTo(revisionChange);
        assertThat(saved.getRevisionDatetime()).isNotNull();
    }

    @Test
    void saveRevision_shouldExtractMrnFromRevisionData() {
        // Arrange
        String revisionKey = "org.rama.entity.Patient^id^12345";
        String revisionEntity = "Patient";
        Map<String, Object> revisionData = new HashMap<>();
        revisionData.put("mrn", "MRN-001");
        revisionData.put("name", "John");

        when(revisionRepository.save(any(Revision.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        revisionService.saveRevision(revisionKey, revisionEntity, revisionData, null);

        // Assert
        verify(revisionRepository).save(revisionCaptor.capture());
        Revision saved = revisionCaptor.getValue();
        assertThat(saved.getMrn()).isEqualTo("MRN-001");
    }

    @Test
    void saveRevision_shouldSetMrnToNull_whenRevisionDataHasNoMrn() {
        // Arrange
        Map<String, Object> revisionData = new HashMap<>();
        revisionData.put("name", "John");

        when(revisionRepository.save(any(Revision.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        revisionService.saveRevision("key", "Entity", revisionData, null);

        // Assert
        verify(revisionRepository).save(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getMrn()).isNull();
    }

    @Test
    void getDirty_shouldDetectChangedFields() {
        // Arrange
        PostUpdateEvent event = mock(PostUpdateEvent.class);
        EntityPersister persister = mock(EntityPersister.class);

        String[] propertyNames = {"name", "age", "timestampField", "userstampField"};
        Object[] oldState = {"John", 30, null, null};
        Object[] newState = {"Jane", 30, null, null};

        Type stringType = mock(Type.class);
        when(stringType.isEntityType()).thenReturn(false);
        when(stringType.isAssociationType()).thenReturn(false);

        Type intType = mock(Type.class);
        when(intType.isEntityType()).thenReturn(false);
        when(intType.isAssociationType()).thenReturn(false);

        Type embeddedType = mock(Type.class);

        Type[] propertyTypes = {stringType, intType, embeddedType, embeddedType};

        when(event.getPersister()).thenReturn(persister);
        when(persister.getPropertyNames()).thenReturn(propertyNames);
        when(persister.getPropertyTypes()).thenReturn(propertyTypes);
        when(event.getOldState()).thenReturn(oldState);
        when(event.getState()).thenReturn(newState);

        // Act
        Map<String, Object> dirty = revisionService.getDirty(event, null);

        // Assert - "name" changed from John to Jane, "age" did not change
        assertThat(dirty).containsKey("name");
        assertThat(dirty.get("name")).isEqualTo("Jane");
        assertThat(dirty).doesNotContainKey("age");
        // timestampField and userstampField should be excluded
        assertThat(dirty).doesNotContainKey("timestampField");
        assertThat(dirty).doesNotContainKey("userstampField");
    }

    @Test
    void getDirty_shouldSkipEntityTypeProperties() {
        // Arrange
        PostUpdateEvent event = mock(PostUpdateEvent.class);
        EntityPersister persister = mock(EntityPersister.class);

        String[] propertyNames = {"name", "relatedEntity"};
        Object[] oldState = {"John", null};
        Object[] newState = {"Jane", "something"};

        Type stringType = mock(Type.class);
        when(stringType.isEntityType()).thenReturn(false);
        when(stringType.isAssociationType()).thenReturn(false);

        Type entityType = mock(Type.class);
        when(entityType.isEntityType()).thenReturn(true);

        Type[] propertyTypes = {stringType, entityType};

        when(event.getPersister()).thenReturn(persister);
        when(persister.getPropertyNames()).thenReturn(propertyNames);
        when(persister.getPropertyTypes()).thenReturn(propertyTypes);
        when(event.getOldState()).thenReturn(oldState);
        when(event.getState()).thenReturn(newState);

        // Act
        Map<String, Object> dirty = revisionService.getDirty(event, null);

        // Assert - entity type properties should be skipped
        assertThat(dirty).containsKey("name");
        assertThat(dirty).doesNotContainKey("relatedEntity");
    }
}

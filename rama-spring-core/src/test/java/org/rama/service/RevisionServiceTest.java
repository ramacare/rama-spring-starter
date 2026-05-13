package org.rama.service;

import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.clickhouse.RevisionClickHouseRepository;
import org.rama.entity.JsonConverter;
import org.rama.service.system.SystemBufferService;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RevisionServiceTest {

    @Mock
    private SystemBufferService systemBufferService;

    @Mock
    private RevisionClickHouseRepository clickHouseRepository;

    private ObjectMapper objectMapper;
    private RevisionService revisionService;

    @Captor
    private ArgumentCaptor<String> payloadCaptor;

    @BeforeEach
    void setUp() {
        objectMapper = JsonConverter.createObjectMapper();
        revisionService = new RevisionService(systemBufferService, objectMapper, clickHouseRepository);
    }

    @Test
    void saveRevision_enqueuesPayload_withCorrectKeys() {
        // Arrange
        String revisionKey = "org.rama.entity.Patient^id^12345";
        String revisionEntity = "Patient";
        Map<String, Object> revisionData = new HashMap<>();
        revisionData.put("name", "John");
        Map<String, Object> revisionChange = new HashMap<>();
        revisionChange.put("name", "Jane");

        // Act
        revisionService.saveRevision(revisionKey, revisionEntity, revisionData, revisionChange);

        // Assert
        verify(systemBufferService).enqueue(eq("revision"), payloadCaptor.capture(), eq("clickhouse:revision"));
        String json = payloadCaptor.getValue();
        assertThat(json).contains("revisionKey");
        assertThat(json).contains("revisionEntity");
        assertThat(json).contains("revisionDatetime");
        assertThat(json).contains("revisionData");
        assertThat(json).contains("revisionChange");
        assertThat(json).contains("12345");
        assertThat(json).contains("Patient");
    }

    @Test
    void saveRevision_returnsEarlyForEmptyData() {
        // Arrange + Act
        revisionService.saveRevision("key", "Entity", null, null);
        revisionService.saveRevision("key", "Entity", Map.of(), null);

        // Assert - no enqueue should happen
        verify(systemBufferService, never()).enqueue(any(), any(), any());
    }

    @Test
    void saveRevision_includesMrnFromData() {
        // Arrange
        Map<String, Object> revisionData = new HashMap<>();
        revisionData.put("mrn", "MRN-001");
        revisionData.put("name", "John");

        // Act
        revisionService.saveRevision("key", "Patient", revisionData, null);

        // Assert
        verify(systemBufferService).enqueue(eq("revision"), payloadCaptor.capture(), eq("clickhouse:revision"));
        String json = payloadCaptor.getValue();
        assertThat(json).contains("MRN-001");
        assertThat(json).contains("mrn");
    }

    @Test
    void getStateAt_returnsEmpty_whenClickHouseRepoNull() {
        // Arrange - build service with null CH repo
        RevisionService serviceWithNoRepo = new RevisionService(systemBufferService, objectMapper, null);

        // Act + Assert
        assertThat(serviceWithNoRepo.getStateAt("key", java.time.OffsetDateTime.now())).isEmpty();
    }

    @Test
    void extractUpdateDirty_shouldDetectChangedFields() {
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
        Map<String, Object> dirty = revisionService.extractUpdateDirty(event, null);

        // Assert - "name" changed from John to Jane, "age" did not change
        assertThat(dirty).containsKey("name");
        assertThat(dirty.get("name")).isEqualTo("Jane");
        assertThat(dirty).doesNotContainKey("age");
        // timestampField and userstampField should be excluded
        assertThat(dirty).doesNotContainKey("timestampField");
        assertThat(dirty).doesNotContainKey("userstampField");
    }

    @Test
    void extractUpdateDirty_shouldSkipEntityTypeProperties() {
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
        Map<String, Object> dirty = revisionService.extractUpdateDirty(event, null);

        // Assert - entity type properties should be skipped
        assertThat(dirty).containsKey("name");
        assertThat(dirty).doesNotContainKey("relatedEntity");
    }
}

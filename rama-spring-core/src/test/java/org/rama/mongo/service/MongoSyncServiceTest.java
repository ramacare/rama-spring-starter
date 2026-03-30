package org.rama.mongo.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.master.MasterItem;
import org.rama.mongo.mapper.MongoMasterItemMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MongoSyncServiceTest {

    @Mock
    private ApplicationContext context;

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private MongoSyncService mongoSyncService;

    @Captor
    private ArgumentCaptor<Object> saveCaptor;

    @Test
    void sync_shouldCreateNewMongoDocument_whenNotExistsInMongo() {
        MasterItem entity = new MasterItem("$Test", "001", "Test Value");
        entity.setId("$Test^001");

        MongoMasterItemMapper mapper = mock(MongoMasterItemMapper.class);
        org.rama.mongo.document.MasterItem mongoDoc = new org.rama.mongo.document.MasterItem();
        mongoDoc.setId("$Test^001");
        mongoDoc.setItemValue("Test Value");

        when(context.getBean(MongoMasterItemMapper.class)).thenReturn(mapper);
        when(mongoTemplate.findById("$Test^001", org.rama.mongo.document.MasterItem.class)).thenReturn(null);
        when(mapper.newMongoEntity(entity)).thenReturn(mongoDoc);

        mongoSyncService.sync(entity);

        verify(mapper).newMongoEntity(entity);
        verify(mapper, never()).updateMongoEntity(any(), any());
        verify(mongoTemplate).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue()).isInstanceOf(org.rama.mongo.document.MasterItem.class);
    }

    @Test
    void sync_shouldUpdateExistingMongoDocument_whenAlreadyExists() {
        MasterItem entity = new MasterItem("$Test", "002", "Updated Value");
        entity.setId("$Test^002");

        MongoMasterItemMapper mapper = mock(MongoMasterItemMapper.class);
        org.rama.mongo.document.MasterItem existingDoc = new org.rama.mongo.document.MasterItem();
        existingDoc.setId("$Test^002");
        existingDoc.setItemValue("Old Value");

        when(context.getBean(MongoMasterItemMapper.class)).thenReturn(mapper);
        when(mongoTemplate.findById("$Test^002", org.rama.mongo.document.MasterItem.class)).thenReturn(existingDoc);

        mongoSyncService.sync(entity);

        verify(mapper).updateMongoEntity(entity, existingDoc);
        verify(mapper, never()).newMongoEntity(any());
        verify(mongoTemplate).save(existingDoc);
    }

    @Test
    void sync_shouldThrow_whenEntityHasNoSyncToMongoAnnotation() {
        Object plainEntity = new Object();

        assertThatThrownBy(() -> mongoSyncService.sync(plainEntity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing @SyncToMongo");
    }
}

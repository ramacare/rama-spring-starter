package org.rama.mongo.listener;

import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.master.MasterItem;
import org.rama.mongo.service.MongoSyncService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MongoSyncListenerTest {

    @Mock
    private MongoSyncService mongoSyncService;

    @InjectMocks
    private GlobalPostInsertSyncToMongoListener insertListener;

    @InjectMocks
    private GlobalPostUpdateSyncToMongoListener updateListener;

    @Test
    void postInsert_shouldTriggerSync_forAnnotatedEntity() {
        MasterItem entity = new MasterItem("$Test", "001", "Value");
        PostInsertEvent event = mock(PostInsertEvent.class);
        when(event.getEntity()).thenReturn(entity);

        insertListener.onPostInsert(event);

        verify(mongoSyncService).sync(entity);
    }

    @Test
    void postInsert_shouldNotTriggerSync_forNonAnnotatedEntity() {
        Object plainEntity = new Object();
        PostInsertEvent event = mock(PostInsertEvent.class);
        when(event.getEntity()).thenReturn(plainEntity);

        insertListener.onPostInsert(event);

        verify(mongoSyncService, never()).sync(plainEntity);
    }

    @Test
    void postUpdate_shouldTriggerSync_forAnnotatedEntity() {
        MasterItem entity = new MasterItem("$Test", "001", "Value");
        PostUpdateEvent event = mock(PostUpdateEvent.class);
        when(event.getEntity()).thenReturn(entity);

        updateListener.onPostUpdate(event);

        verify(mongoSyncService).sync(entity);
    }

    @Test
    void postUpdate_shouldNotTriggerSync_forNonAnnotatedEntity() {
        Object plainEntity = new Object();
        PostUpdateEvent event = mock(PostUpdateEvent.class);
        when(event.getEntity()).thenReturn(plainEntity);

        updateListener.onPostUpdate(event);

        verify(mongoSyncService, never()).sync(plainEntity);
    }

    @Test
    void requiresPostCommitHandling_shouldReturnFalse() {
        EntityPersister persister = mock(EntityPersister.class);
        assertThat(insertListener.requiresPostCommitHandling(persister)).isFalse();
        assertThat(updateListener.requiresPostCommitHandling(persister)).isFalse();
    }
}

package org.rama.listener.global;

import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.annotation.EntityEvent;
import org.rama.event.EntityCreated;
import org.rama.event.EntityEmptyEvent;
import org.rama.event.EntityUpdated;
import org.rama.event.IEntityEvent;
import org.rama.service.EntityEventService;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class EntityEventListenerTest {

    @Mock
    private ObjectProvider<EntityEventService> entityEventServiceProvider;

    @Mock
    private EntityEventService entityEventService;

    // ---- Test fixtures ----

    @EntityEvent(createdEvent = AnnotatedCreated.class, updatedEvent = AnnotatedUpdated.class, afterCommit = false)
    static class AnnotatedEntity {
    }

    @EntityEvent
    static class DefaultEventEntity {
    }

    @EntityEvent(createdEvent = EntityEmptyEvent.class, updatedEvent = EntityEmptyEvent.class)
    static class OptedOutEntity {
    }

    static class PlainEntity {
    }

    public static class AnnotatedCreated implements IEntityEvent<AnnotatedEntity> {
        private final AnnotatedEntity entity;

        public AnnotatedCreated(AnnotatedEntity entity) {
            this.entity = entity;
        }

        @Override
        public AnnotatedEntity getEntity() {
            return entity;
        }
    }

    public static class AnnotatedUpdated implements IEntityEvent<AnnotatedEntity> {
        private final AnnotatedEntity entity;

        public AnnotatedUpdated(AnnotatedEntity entity) {
            this.entity = entity;
        }

        @Override
        public AnnotatedEntity getEntity() {
            return entity;
        }
    }

    // ---- POST_INSERT ----

    @Test
    void postInsert_shouldPublishCreatedEvent_forAnnotatedEntity() {
        when(entityEventServiceProvider.getIfAvailable()).thenReturn(entityEventService);
        GlobalPostInsertEntityEventListener listener =
                new GlobalPostInsertEntityEventListener(entityEventServiceProvider);

        AnnotatedEntity entity = new AnnotatedEntity();
        PostInsertEvent event = mock(PostInsertEvent.class);
        when(event.getEntity()).thenReturn(entity);

        listener.onPostInsert(event);

        verify(entityEventService).publishEntityEvent(AnnotatedCreated.class, entity, false);
    }

    @Test
    void postInsert_shouldUseDefaultCreatedEvent_whenAnnotationOmitsValues() {
        when(entityEventServiceProvider.getIfAvailable()).thenReturn(entityEventService);
        GlobalPostInsertEntityEventListener listener =
                new GlobalPostInsertEntityEventListener(entityEventServiceProvider);

        DefaultEventEntity entity = new DefaultEventEntity();
        PostInsertEvent event = mock(PostInsertEvent.class);
        when(event.getEntity()).thenReturn(entity);

        listener.onPostInsert(event);

        verify(entityEventService).publishEntityEvent(EntityCreated.class, entity, true);
    }

    @Test
    void postInsert_shouldSkip_forNonAnnotatedEntity() {
        GlobalPostInsertEntityEventListener listener =
                new GlobalPostInsertEntityEventListener(entityEventServiceProvider);

        PostInsertEvent event = mock(PostInsertEvent.class);
        when(event.getEntity()).thenReturn(new PlainEntity());

        listener.onPostInsert(event);

        verifyNoInteractions(entityEventServiceProvider);
    }

    @Test
    void postInsert_shouldSkip_whenCreatedEventIsEntityEmptyEvent() {
        GlobalPostInsertEntityEventListener listener =
                new GlobalPostInsertEntityEventListener(entityEventServiceProvider);

        PostInsertEvent event = mock(PostInsertEvent.class);
        when(event.getEntity()).thenReturn(new OptedOutEntity());

        listener.onPostInsert(event);

        verify(entityEventServiceProvider, never()).getIfAvailable();
    }

    @Test
    void postInsert_shouldNotFail_whenServiceUnavailable() {
        when(entityEventServiceProvider.getIfAvailable()).thenReturn(null);
        GlobalPostInsertEntityEventListener listener =
                new GlobalPostInsertEntityEventListener(entityEventServiceProvider);

        PostInsertEvent event = mock(PostInsertEvent.class);
        when(event.getEntity()).thenReturn(new AnnotatedEntity());

        listener.onPostInsert(event);
    }

    @Test
    void postInsert_shouldSkip_whenEntityIsNull() {
        GlobalPostInsertEntityEventListener listener =
                new GlobalPostInsertEntityEventListener(entityEventServiceProvider);

        PostInsertEvent event = mock(PostInsertEvent.class);
        when(event.getEntity()).thenReturn(null);

        listener.onPostInsert(event);

        verifyNoInteractions(entityEventServiceProvider);
    }

    // ---- POST_UPDATE ----

    @Test
    void postUpdate_shouldPublishUpdatedEvent_forAnnotatedEntity() {
        when(entityEventServiceProvider.getIfAvailable()).thenReturn(entityEventService);
        GlobalPostUpdateEntityEventListener listener =
                new GlobalPostUpdateEntityEventListener(entityEventServiceProvider);

        AnnotatedEntity entity = new AnnotatedEntity();
        PostUpdateEvent event = mock(PostUpdateEvent.class);
        when(event.getEntity()).thenReturn(entity);

        listener.onPostUpdate(event);

        verify(entityEventService).publishEntityEvent(AnnotatedUpdated.class, entity, false);
    }

    @Test
    void postUpdate_shouldUseDefaultUpdatedEvent_whenAnnotationOmitsValues() {
        when(entityEventServiceProvider.getIfAvailable()).thenReturn(entityEventService);
        GlobalPostUpdateEntityEventListener listener =
                new GlobalPostUpdateEntityEventListener(entityEventServiceProvider);

        DefaultEventEntity entity = new DefaultEventEntity();
        PostUpdateEvent event = mock(PostUpdateEvent.class);
        when(event.getEntity()).thenReturn(entity);

        listener.onPostUpdate(event);

        verify(entityEventService).publishEntityEvent(EntityUpdated.class, entity, true);
    }

    @Test
    void postUpdate_shouldSkip_forNonAnnotatedEntity() {
        GlobalPostUpdateEntityEventListener listener =
                new GlobalPostUpdateEntityEventListener(entityEventServiceProvider);

        PostUpdateEvent event = mock(PostUpdateEvent.class);
        when(event.getEntity()).thenReturn(new PlainEntity());

        listener.onPostUpdate(event);

        verifyNoInteractions(entityEventServiceProvider);
    }

    @Test
    void postUpdate_shouldSkip_whenUpdatedEventIsEntityEmptyEvent() {
        GlobalPostUpdateEntityEventListener listener =
                new GlobalPostUpdateEntityEventListener(entityEventServiceProvider);

        PostUpdateEvent event = mock(PostUpdateEvent.class);
        when(event.getEntity()).thenReturn(new OptedOutEntity());

        listener.onPostUpdate(event);

        verify(entityEventServiceProvider, never()).getIfAvailable();
    }

    // ---- requiresPostCommitHandling ----

    @Test
    void requiresPostCommitHandling_shouldReturnFalse() {
        GlobalPostInsertEntityEventListener insertListener =
                new GlobalPostInsertEntityEventListener(entityEventServiceProvider);
        GlobalPostUpdateEntityEventListener updateListener =
                new GlobalPostUpdateEntityEventListener(entityEventServiceProvider);
        EntityPersister persister = mock(EntityPersister.class);

        assertThat(insertListener.requiresPostCommitHandling(persister)).isFalse();
        assertThat(updateListener.requiresPostCommitHandling(persister)).isFalse();
    }
}

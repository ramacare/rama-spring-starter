package org.rama.listener.global;

import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.annotation.TrackRevision;
import org.rama.entity.master.MasterItem;
import org.rama.service.RevisionService;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RevisionListenerTest {

    @Mock
    private ObjectProvider<RevisionService> revisionServiceProvider;

    @Mock
    private RevisionService revisionService;

    @Test
    void postInsert_shouldSaveRevision_forAnnotatedEntity() {
        when(revisionServiceProvider.getIfAvailable()).thenReturn(revisionService);
        GlobalPostInsertRevisionListener listener = new GlobalPostInsertRevisionListener(revisionServiceProvider);

        MasterItem entity = new MasterItem("$Test", "001", "Value");
        PostInsertEvent event = mock(PostInsertEvent.class);
        when(event.getEntity()).thenReturn(entity);

        listener.onPostInsert(event);

        verify(revisionService).saveRevision(event);
    }

    @Test
    void postInsert_shouldNotSaveRevision_forNonAnnotatedEntity() {
        GlobalPostInsertRevisionListener listener = new GlobalPostInsertRevisionListener(revisionServiceProvider);

        Object plainEntity = new Object();
        PostInsertEvent event = mock(PostInsertEvent.class);
        when(event.getEntity()).thenReturn(plainEntity);

        listener.onPostInsert(event);

        verify(revisionServiceProvider, never()).getIfAvailable();
    }

    @Test
    void postInsert_shouldNotFail_whenRevisionServiceIsUnavailable() {
        when(revisionServiceProvider.getIfAvailable()).thenReturn(null);
        GlobalPostInsertRevisionListener listener = new GlobalPostInsertRevisionListener(revisionServiceProvider);

        MasterItem entity = new MasterItem("$Test", "001", "Value");
        PostInsertEvent event = mock(PostInsertEvent.class);
        when(event.getEntity()).thenReturn(entity);

        // Should not throw
        listener.onPostInsert(event);
    }

    @Test
    void postUpdate_shouldSaveRevision_forAnnotatedEntity() {
        when(revisionServiceProvider.getIfAvailable()).thenReturn(revisionService);
        GlobalPostUpdateRevisionListener listener = new GlobalPostUpdateRevisionListener(revisionServiceProvider);

        MasterItem entity = new MasterItem("$Test", "001", "Value");
        PostUpdateEvent event = mock(PostUpdateEvent.class);
        when(event.getEntity()).thenReturn(entity);

        listener.onPostUpdate(event);

        // MasterItem has @TrackRevision with default empty fields
        TrackRevision annotation = MasterItem.class.getAnnotation(TrackRevision.class);
        verify(revisionService).saveRevision(event, annotation.value());
    }

    @Test
    void postUpdate_shouldNotSaveRevision_forNonAnnotatedEntity() {
        GlobalPostUpdateRevisionListener listener = new GlobalPostUpdateRevisionListener(revisionServiceProvider);

        Object plainEntity = new Object();
        PostUpdateEvent event = mock(PostUpdateEvent.class);
        when(event.getEntity()).thenReturn(plainEntity);

        listener.onPostUpdate(event);

        verify(revisionServiceProvider, never()).getIfAvailable();
    }

    @Test
    void requiresPostCommitHandling_shouldReturnFalse() {
        GlobalPostInsertRevisionListener insertListener = new GlobalPostInsertRevisionListener(revisionServiceProvider);
        GlobalPostUpdateRevisionListener updateListener = new GlobalPostUpdateRevisionListener(revisionServiceProvider);
        EntityPersister persister = mock(EntityPersister.class);

        assertThat(insertListener.requiresPostCommitHandling(persister)).isFalse();
        assertThat(updateListener.requiresPostCommitHandling(persister)).isFalse();
    }
}

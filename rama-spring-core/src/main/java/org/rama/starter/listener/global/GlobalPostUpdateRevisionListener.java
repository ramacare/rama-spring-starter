package org.rama.starter.listener.global;

import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.starter.annotation.TrackRevision;
import org.rama.starter.service.RevisionService;

public class GlobalPostUpdateRevisionListener implements PostUpdateEventListener {
    private final RevisionService revisionService;

    public GlobalPostUpdateRevisionListener(RevisionService revisionService) {
        this.revisionService = revisionService;
    }

    @Override
    public void onPostUpdate(PostUpdateEvent postUpdateEvent) {
        if (postUpdateEvent.getEntity().getClass().isAnnotationPresent(TrackRevision.class)) {
            TrackRevision trackRevision = postUpdateEvent.getEntity().getClass().getAnnotation(TrackRevision.class);
            revisionService.saveRevision(postUpdateEvent, trackRevision.value());
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return false;
    }
}

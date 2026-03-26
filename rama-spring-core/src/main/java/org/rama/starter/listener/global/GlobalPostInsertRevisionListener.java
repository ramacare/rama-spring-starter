package org.rama.starter.listener.global;

import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.starter.annotation.TrackRevision;
import org.rama.starter.service.RevisionService;

public class GlobalPostInsertRevisionListener implements PostInsertEventListener {
    private final RevisionService revisionService;

    public GlobalPostInsertRevisionListener(RevisionService revisionService) {
        this.revisionService = revisionService;
    }

    @Override
    public void onPostInsert(PostInsertEvent postInsertEvent) {
        if (postInsertEvent.getEntity().getClass().isAnnotationPresent(TrackRevision.class)) {
            revisionService.saveRevision(postInsertEvent);
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return false;
    }
}

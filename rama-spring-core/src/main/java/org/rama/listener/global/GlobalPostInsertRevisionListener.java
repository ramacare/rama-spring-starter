package org.rama.listener.global;

import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.annotation.TrackRevision;
import org.rama.service.RevisionService;
import org.springframework.beans.factory.ObjectProvider;

public class GlobalPostInsertRevisionListener implements PostInsertEventListener {
    private final ObjectProvider<RevisionService> revisionServiceProvider;

    public GlobalPostInsertRevisionListener(ObjectProvider<RevisionService> revisionServiceProvider) {
        this.revisionServiceProvider = revisionServiceProvider;
    }

    @Override
    public void onPostInsert(PostInsertEvent postInsertEvent) {
        if (postInsertEvent.getEntity().getClass().isAnnotationPresent(TrackRevision.class)) {
            RevisionService revisionService = revisionServiceProvider.getIfAvailable();
            if (revisionService != null) {
                revisionService.saveRevision(postInsertEvent);
            }
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return false;
    }
}

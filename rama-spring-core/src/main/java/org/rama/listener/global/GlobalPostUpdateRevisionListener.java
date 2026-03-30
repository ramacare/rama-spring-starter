package org.rama.listener.global;

import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.annotation.TrackRevision;
import org.rama.service.RevisionService;
import org.springframework.beans.factory.ObjectProvider;

public class GlobalPostUpdateRevisionListener implements PostUpdateEventListener {
    private final ObjectProvider<RevisionService> revisionServiceProvider;

    public GlobalPostUpdateRevisionListener(ObjectProvider<RevisionService> revisionServiceProvider) {
        this.revisionServiceProvider = revisionServiceProvider;
    }

    @Override
    public void onPostUpdate(PostUpdateEvent postUpdateEvent) {
        if (postUpdateEvent.getEntity().getClass().isAnnotationPresent(TrackRevision.class)) {
            TrackRevision trackRevision = postUpdateEvent.getEntity().getClass().getAnnotation(TrackRevision.class);
            RevisionService revisionService = revisionServiceProvider.getIfAvailable();
            if (revisionService != null) {
                revisionService.saveRevision(postUpdateEvent, trackRevision.value());
            }
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return false;
    }
}

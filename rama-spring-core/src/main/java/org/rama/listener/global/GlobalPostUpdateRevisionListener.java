package org.rama.listener.global;

import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.annotation.TrackRevision;
import org.rama.service.RevisionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

public class GlobalPostUpdateRevisionListener implements PostUpdateEventListener {
    private final ObjectProvider<RevisionService> revisionServiceProvider;

    public GlobalPostUpdateRevisionListener(ObjectProvider<RevisionService> revisionServiceProvider) {
        this.revisionServiceProvider = revisionServiceProvider;
    }

    @Override
    public void onPostUpdate(PostUpdateEvent postUpdateEvent) {
        if (postUpdateEvent.getEntity().getClass().isAnnotationPresent(TrackRevision.class)) {
            RevisionService revisionService = revisionServiceProvider.getIfAvailable();
            if (revisionService != null) {
                TrackRevision trackRevision = postUpdateEvent.getEntity().getClass().getAnnotation(TrackRevision.class);
                String revisionKey = revisionService.buildRevisionKey(postUpdateEvent.getPersister(), postUpdateEvent.getId());
                String revisionEntity = revisionService.resolveEntityName(postUpdateEvent.getPersister().getEntityName());
                Map<String, Object> dirty = revisionService.extractUpdateDirty(postUpdateEvent, trackRevision.value());
                Map<String, Object> data = revisionService.extractUpdateData(postUpdateEvent);

                if (!dirty.isEmpty()) {
                    if (TransactionSynchronizationManager.isSynchronizationActive()) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                revisionService.saveRevision(revisionKey, revisionEntity, data, dirty);
                            }
                        });
                    } else {
                        revisionService.saveRevision(revisionKey, revisionEntity, data, dirty);
                    }
                }
            }
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return false;
    }
}

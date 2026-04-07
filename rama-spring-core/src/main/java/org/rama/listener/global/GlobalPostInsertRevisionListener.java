package org.rama.listener.global;

import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.annotation.TrackRevision;
import org.rama.service.RevisionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

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
                String revisionKey = revisionService.buildRevisionKey(postInsertEvent.getPersister(), postInsertEvent.getId());
                String revisionEntity = revisionService.resolveEntityName(postInsertEvent.getPersister().getEntityName());
                Map<String, Object> data = revisionService.extractInsertData(postInsertEvent);

                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            revisionService.saveRevision(revisionKey, revisionEntity, data, null);
                        }
                    });
                } else {
                    revisionService.saveRevision(revisionKey, revisionEntity, data, null);
                }
            }
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return false;
    }
}

package org.rama.meilisearch.listener;

import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.annotation.SyncToMeilisearch;
import org.rama.meilisearch.service.MeilisearchService;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class GlobalPostUpdateMeilisearchListener implements PostUpdateEventListener {
    private final MeilisearchService meilisearchService;

    public GlobalPostUpdateMeilisearchListener(MeilisearchService meilisearchService) {
        this.meilisearchService = meilisearchService;
    }

    @Override
    public void onPostUpdate(PostUpdateEvent postUpdateEvent) {
        Object entity = postUpdateEvent.getEntity();
        if (entity.getClass().isAnnotationPresent(SyncToMeilisearch.class)) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        meilisearchService.sync(entity);
                    }
                });
            } else {
                meilisearchService.sync(entity);
            }
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return true;
    }
}

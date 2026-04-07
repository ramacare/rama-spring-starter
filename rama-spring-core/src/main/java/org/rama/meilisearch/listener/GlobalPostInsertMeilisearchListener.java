package org.rama.meilisearch.listener;

import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.annotation.SyncToMeilisearch;
import org.rama.meilisearch.service.MeilisearchService;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class GlobalPostInsertMeilisearchListener implements PostInsertEventListener {
    private final MeilisearchService meilisearchService;

    public GlobalPostInsertMeilisearchListener(MeilisearchService meilisearchService) {
        this.meilisearchService = meilisearchService;
    }

    @Override
    public void onPostInsert(PostInsertEvent postInsertEvent) {
        Object entity = postInsertEvent.getEntity();
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

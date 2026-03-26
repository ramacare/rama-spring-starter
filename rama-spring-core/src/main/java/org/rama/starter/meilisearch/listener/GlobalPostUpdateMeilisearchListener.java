package org.rama.starter.meilisearch.listener;

import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.starter.annotation.SyncToMeilisearch;
import org.rama.starter.meilisearch.service.MeilisearchService;

public class GlobalPostUpdateMeilisearchListener implements PostUpdateEventListener {
    private final MeilisearchService meilisearchService;

    public GlobalPostUpdateMeilisearchListener(MeilisearchService meilisearchService) {
        this.meilisearchService = meilisearchService;
    }

    @Override
    public void onPostUpdate(PostUpdateEvent postUpdateEvent) {
        Object entity = postUpdateEvent.getEntity();
        if (entity.getClass().isAnnotationPresent(SyncToMeilisearch.class)) {
            meilisearchService.sync(entity);
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return false;
    }
}

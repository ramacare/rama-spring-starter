package org.rama.mongo.listener;

import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.annotation.SyncToMongo;
import org.rama.mongo.service.MongoSyncService;

public class GlobalPostUpdateSyncToMongoListener implements PostUpdateEventListener {
    private final MongoSyncService mongoSyncService;

    public GlobalPostUpdateSyncToMongoListener(MongoSyncService mongoSyncService) {
        this.mongoSyncService = mongoSyncService;
    }

    @Override
    public void onPostUpdate(PostUpdateEvent postUpdateEvent) {
        Object entity = postUpdateEvent.getEntity();
        if (entity.getClass().isAnnotationPresent(SyncToMongo.class)) {
            mongoSyncService.sync(entity);
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return false;
    }
}

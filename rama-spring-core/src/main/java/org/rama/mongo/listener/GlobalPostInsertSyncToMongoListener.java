package org.rama.mongo.listener;

import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.annotation.SyncToMongo;
import org.rama.mongo.service.MongoSyncService;

public class GlobalPostInsertSyncToMongoListener implements PostInsertEventListener {
    private final MongoSyncService mongoSyncService;

    public GlobalPostInsertSyncToMongoListener(MongoSyncService mongoSyncService) {
        this.mongoSyncService = mongoSyncService;
    }

    @Override
    public void onPostInsert(PostInsertEvent postInsertEvent) {
        Object entity = postInsertEvent.getEntity();
        if (entity.getClass().isAnnotationPresent(SyncToMongo.class)) {
            mongoSyncService.sync(entity);
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return false;
    }
}

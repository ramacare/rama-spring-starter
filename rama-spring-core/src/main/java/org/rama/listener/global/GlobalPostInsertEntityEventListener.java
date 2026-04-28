package org.rama.listener.global;

import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.annotation.EntityEvent;
import org.rama.event.EntityEmptyEvent;
import org.rama.service.EntityEventService;
import org.springframework.beans.factory.ObjectProvider;

public class GlobalPostInsertEntityEventListener implements PostInsertEventListener {
    private final ObjectProvider<EntityEventService> entityEventServiceProvider;

    public GlobalPostInsertEntityEventListener(ObjectProvider<EntityEventService> entityEventServiceProvider) {
        this.entityEventServiceProvider = entityEventServiceProvider;
    }

    @Override
    public void onPostInsert(PostInsertEvent postInsertEvent) {
        Object entity = postInsertEvent.getEntity();
        if (entity == null) return;

        Class<?> entityClass = entity.getClass();
        if (!entityClass.isAnnotationPresent(EntityEvent.class)) return;

        EntityEvent entityEvent = entityClass.getAnnotation(EntityEvent.class);
        if (entityEvent.createdEvent() == EntityEmptyEvent.class) return;

        EntityEventService entityEventService = entityEventServiceProvider.getIfAvailable();
        if (entityEventService == null) return;

        entityEventService.publishEntityEvent(
                entityEvent.createdEvent(),
                entity,
                entityEvent.afterCommit()
        );
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return false;
    }
}

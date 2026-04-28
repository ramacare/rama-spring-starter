package org.rama.listener.global;

import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.rama.annotation.EntityEvent;
import org.rama.event.EntityEmptyEvent;
import org.rama.service.EntityEventService;
import org.springframework.beans.factory.ObjectProvider;

public class GlobalPostUpdateEntityEventListener implements PostUpdateEventListener {
    private final ObjectProvider<EntityEventService> entityEventServiceProvider;

    public GlobalPostUpdateEntityEventListener(ObjectProvider<EntityEventService> entityEventServiceProvider) {
        this.entityEventServiceProvider = entityEventServiceProvider;
    }

    @Override
    public void onPostUpdate(PostUpdateEvent postUpdateEvent) {
        Object entity = postUpdateEvent.getEntity();
        if (entity == null) return;

        Class<?> entityClass = entity.getClass();
        if (!entityClass.isAnnotationPresent(EntityEvent.class)) return;

        EntityEvent entityEvent = entityClass.getAnnotation(EntityEvent.class);
        if (entityEvent.updatedEvent() == EntityEmptyEvent.class) return;

        EntityEventService entityEventService = entityEventServiceProvider.getIfAvailable();
        if (entityEventService == null) return;

        entityEventService.publishEntityEvent(
                entityEvent.updatedEvent(),
                entity,
                entityEvent.afterCommit()
        );
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister entityPersister) {
        return false;
    }
}

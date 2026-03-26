package org.rama.starter.util;

import org.hibernate.event.spi.PreDeleteEvent;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HibernateUtil {
    private static final ConcurrentHashMap<String, Map<String, Integer>> INDEX_CACHE = new ConcurrentHashMap<>();

    private HibernateUtil() {
    }

    public static void setState(PreInsertEvent event, String propertyName, Object value) {
        event.getState()[propertyIndex(event.getPersister(), propertyName)] = value;
    }

    public static void setState(PreUpdateEvent event, String propertyName, Object value) {
        event.getState()[propertyIndex(event.getPersister(), propertyName)] = value;
    }

    public static void setState(PreDeleteEvent event, String propertyName, Object value) {
        Object[] deletedState = event.getDeletedState();
        if (deletedState == null) {
            throw new IllegalStateException("PreDeleteEvent.deletedState is null for entity " + event.getPersister().getEntityName());
        }
        deletedState[propertyIndex(event.getPersister(), propertyName)] = value;
    }

    public static void clearIndexCache() {
        INDEX_CACHE.clear();
    }

    private static int propertyIndex(EntityPersister persister, String propertyName) {
        Map<String, Integer> map = INDEX_CACHE.computeIfAbsent(persister.getEntityName(), key -> buildIndexMap(persister));
        Integer idx = map.get(propertyName);
        if (idx == null) {
            throw new IllegalArgumentException("Property '" + propertyName + "' not found in entity " + persister.getEntityName());
        }
        return idx;
    }

    private static Map<String, Integer> buildIndexMap(EntityPersister persister) {
        String[] props = persister.getPropertyNames();
        Map<String, Integer> map = new ConcurrentHashMap<>(Math.max(16, (int) (props.length / 0.75f) + 1));
        for (int i = 0; i < props.length; i++) {
            map.put(props[i], i);
        }
        return map;
    }
}

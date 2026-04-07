package org.rama.service;

import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.Type;
import org.rama.entity.Revision;
import org.rama.repository.revision.RevisionRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.*;

public class RevisionService {
    private final RevisionRepository revisionRepository;

    public RevisionService(RevisionRepository revisionRepository) {
        this.revisionRepository = revisionRepository;
    }

    @Async
    @Transactional
    public void saveRevision(String revisionKey, String revisionEntity, Map<String, Object> revisionData, Map<String, Object> revisionChange) {
        if (revisionData == null || revisionData.isEmpty()) {
            return;
        }
        Revision revision = new Revision();
        revision.setRevisionKey(revisionKey);
        revision.setRevisionEntity(revisionEntity);
        revision.setRevisionDatetime(OffsetDateTime.now());
        revision.setRevisionData(revisionData);
        revision.setRevisionChange(revisionChange);
        if (revisionData.containsKey("mrn")) {
            revision.setMrn(Objects.toString(revisionData.get("mrn"), null));
        }
        revisionRepository.save(revision);
    }

    public void saveRevision(PostInsertEvent postInsertEvent) {
        String revisionKey = buildRevisionKey(postInsertEvent.getPersister(), postInsertEvent.getId());
        String revisionEntity = resolveEntityName(postInsertEvent.getPersister().getEntityName());
        Map<String, Object> current = getCurrent(postInsertEvent);

        saveAfterCommit(revisionKey, revisionEntity, current, null);
    }

    public void saveRevision(PostUpdateEvent postUpdateEvent, String[] fields) {
        String revisionKey = buildRevisionKey(postUpdateEvent.getPersister(), postUpdateEvent.getId());
        String revisionEntity = resolveEntityName(postUpdateEvent.getPersister().getEntityName());
        Map<String, Object> dirty = getDirty(postUpdateEvent, fields);
        Map<String, Object> current = getCurrent(postUpdateEvent);

        if (!dirty.isEmpty()) {
            saveAfterCommit(revisionKey, revisionEntity, current, dirty);
        }
    }

    private void saveAfterCommit(String revisionKey, String revisionEntity, Map<String, Object> revisionData, Map<String, Object> revisionChange) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    saveRevision(revisionKey, revisionEntity, revisionData, revisionChange);
                }
            });
        } else {
            saveRevision(revisionKey, revisionEntity, revisionData, revisionChange);
        }
    }

    private String buildRevisionKey(EntityPersister persister, Object id) {
        return persister.getEntityName() + "^" + persister.getIdentifierPropertyName() + "^" + id;
    }

    protected Map<String, Object> getDirty(PostUpdateEvent event, String[] fields) {
        Map<String, Object> dirty = new HashMap<>();
        String[] propertyNames = event.getPersister().getPropertyNames();
        Object[] oldStates = event.getOldState();
        Object[] newStates = event.getState();
        Type[] propertyTypes = event.getPersister().getPropertyTypes();

        List<String> fieldsList = (fields != null && fields.length > 0)
                ? new ArrayList<>(Arrays.asList(fields))
                : new ArrayList<>(Arrays.asList(propertyNames));
        fieldsList.remove("timestampField");
        fieldsList.remove("userstampField");

        boolean hasOld = oldStates != null && oldStates.length == propertyNames.length;
        for (int i = 0; i < propertyNames.length; i++) {
            String prop = propertyNames[i];
            if (!fieldsList.contains(prop)) {
                continue;
            }
            if (propertyTypes[i].isEntityType() || propertyTypes[i].isAssociationType()) {
                continue;
            }
            Object oldVal = hasOld ? oldStates[i] : null;
            Object newVal = newStates[i];
            if (!Objects.deepEquals(oldVal, newVal) && (hasOld || newVal != null)) {
                dirty.put(prop, newVal);
            }
        }
        return dirty;
    }

    protected Map<String, Object> getCurrent(PostUpdateEvent postUpdateEvent) {
        Map<String, Object> currentMap = getStateMap(postUpdateEvent.getPersister(), postUpdateEvent.getState());
        currentMap.put(postUpdateEvent.getPersister().getIdentifierPropertyName(), postUpdateEvent.getId());
        return currentMap;
    }

    protected Map<String, Object> getCurrent(PostInsertEvent postInsertEvent) {
        Map<String, Object> currentMap = getStateMap(postInsertEvent.getPersister(), postInsertEvent.getState());
        currentMap.put(postInsertEvent.getPersister().getIdentifierPropertyName(), postInsertEvent.getId());
        return currentMap;
    }

    protected Map<String, Object> getStateMap(EntityPersister entityPersister, Object[] state) {
        Map<String, Object> original = new HashMap<>();
        String[] propertyNames = entityPersister.getPropertyNames();
        Type[] propertyTypes = entityPersister.getPropertyTypes();

        for (int i = 0; i < propertyNames.length; i++) {
            if (!propertyTypes[i].isEntityType() && !propertyTypes[i].isAssociationType()) {
                original.put(propertyNames[i], state[i]);
            }
        }
        return original;
    }

    private String resolveEntityName(String entityName) {
        return entityName.substring(entityName.lastIndexOf('.') + 1);
    }
}

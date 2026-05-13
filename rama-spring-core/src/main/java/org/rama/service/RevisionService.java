package org.rama.service;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.Type;
import org.rama.clickhouse.ClickHouseRevisionRecord;
import org.rama.clickhouse.RevisionClickHouseRepository;
import org.rama.entity.Revision;
import org.rama.entity.UserstampField;
import org.rama.service.system.SystemBufferService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
public class RevisionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final SystemBufferService systemBufferService;
    private final ObjectMapper objectMapper;
    private final RevisionClickHouseRepository clickHouseRepository;

    public RevisionService(SystemBufferService systemBufferService,
                           ObjectMapper objectMapper,
                           RevisionClickHouseRepository clickHouseRepository) {
        this.systemBufferService = systemBufferService;
        this.objectMapper = objectMapper;
        this.clickHouseRepository = clickHouseRepository;
    }

    /**
     * Enqueue a revision into system_buffer. Runs in a new transaction so the
     * buffer INSERT is committed independently. The drain job ships rows to
     * ClickHouse asynchronously.
     *
     * <p>Note: the listener calls this from an afterCommit synchronization
     * (post-entity-commit), so no active transaction exists at that point.
     * REQUIRES_NEW starts a fresh transaction for the buffer write.</p>
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveRevision(String revisionKey, String revisionEntity,
                             Map<String, Object> revisionData,
                             Map<String, Object> revisionChange) {
        if (revisionData == null || revisionData.isEmpty()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("revisionKey", revisionKey);
        payload.put("revisionEntity", revisionEntity);
        payload.put("revisionDatetime", OffsetDateTime.now().toString());
        payload.put("revisionData", revisionData);
        payload.put("revisionChange", revisionChange);
        if (revisionData.containsKey("mrn")) {
            payload.put("mrn", Objects.toString(revisionData.get("mrn"), null));
        }
        Object userstamp = revisionData.get("userstampField");
        if (userstamp instanceof UserstampField u) {
            if (u.getCreatedBy() != null) payload.put("createdBy", u.getCreatedBy());
            if (u.getUpdatedBy() != null) payload.put("updatedBy", u.getUpdatedBy());
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.warn("Failed to serialize revision payload; skipping", e);
            return;
        }
        systemBufferService.enqueue("revision", json, "clickhouse:revision");
    }

    public Optional<Revision> getStateAt(String revisionKey, OffsetDateTime at) {
        if (clickHouseRepository == null) return Optional.empty();
        try {
            return clickHouseRepository.getStateAt(revisionKey, at).map(this::fromClickHouse);
        } catch (RuntimeException e) {
            log.warn("ClickHouse getStateAt failed for key={}, at={}. Cause: {}",
                    revisionKey, at, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Revision> findHistory(String revisionKey) {
        if (clickHouseRepository == null) return List.of();
        try {
            return clickHouseRepository.findHistory(revisionKey).stream()
                    .map(this::fromClickHouse).toList();
        } catch (RuntimeException e) {
            log.warn("ClickHouse findHistory failed for key={}. Cause: {}", revisionKey, e.getMessage());
            return List.of();
        }
    }

    public List<Revision> findByEntityAndMrn(String revisionEntity, String mrn) {
        if (clickHouseRepository == null) return List.of();
        try {
            return clickHouseRepository.findByEntityAndMrn(revisionEntity, mrn).stream()
                    .map(this::fromClickHouse).toList();
        } catch (RuntimeException e) {
            log.warn("ClickHouse findByEntityAndMrn failed. Cause: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Revision> findByEntityInAndMrn(List<String> revisionEntities, String mrn) {
        if (clickHouseRepository == null) return List.of();
        try {
            return clickHouseRepository.findByEntityInAndMrn(revisionEntities, mrn).stream()
                    .map(this::fromClickHouse).toList();
        } catch (RuntimeException e) {
            log.warn("ClickHouse findByEntityInAndMrn failed. Cause: {}", e.getMessage());
            return List.of();
        }
    }

    private Revision fromClickHouse(ClickHouseRevisionRecord r) {
        Revision rev = new Revision();
        rev.setRevisionKey(r.revisionKey());
        rev.setRevisionEntity(r.revisionEntity());
        rev.setMrn(r.mrn());
        rev.setRevisionDatetime(r.revisionDatetime());
        rev.setRevisionData(parseJsonMap(r.revisionData()));
        rev.setRevisionChange(parseJsonMap(r.revisionChange()));
        rev.setCreatedBy(r.createdBy());
        rev.setUpdatedBy(r.updatedBy());
        return rev;
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, MAP_TYPE); }
        catch (RuntimeException e) {
            log.debug("Failed to parse revision JSON: {}", e.getMessage());
            return null;
        }
    }

    // --- Hibernate helper methods ---

    public String buildRevisionKey(EntityPersister persister, Object id) {
        return persister.getEntityName() + "^" + persister.getIdentifierPropertyName() + "^" + id;
    }

    public String resolveEntityName(String entityName) {
        return entityName.substring(entityName.lastIndexOf('.') + 1);
    }

    public Map<String, Object> extractInsertData(PostInsertEvent event) {
        Map<String, Object> current = getStateMap(event.getPersister(), event.getState());
        current.put(event.getPersister().getIdentifierPropertyName(), event.getId());
        return current;
    }

    public Map<String, Object> extractUpdateData(PostUpdateEvent event) {
        Map<String, Object> current = getStateMap(event.getPersister(), event.getState());
        current.put(event.getPersister().getIdentifierPropertyName(), event.getId());
        return current;
    }

    public Map<String, Object> extractUpdateDirty(PostUpdateEvent event, String[] fields) {
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
            if (!fieldsList.contains(prop)) continue;
            if (propertyTypes[i].isEntityType() || propertyTypes[i].isAssociationType()) continue;
            Object oldVal = hasOld ? oldStates[i] : null;
            Object newVal = newStates[i];
            if (!Objects.deepEquals(oldVal, newVal) && (hasOld || newVal != null)) {
                dirty.put(prop, newVal);
            }
        }
        return dirty;
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
}

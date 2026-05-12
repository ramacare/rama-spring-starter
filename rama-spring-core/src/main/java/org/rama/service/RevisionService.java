package org.rama.service;

import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.Type;
import org.rama.clickhouse.ClickHouseRevisionRecord;
import org.rama.clickhouse.RevisionClickHouseRepository;
import org.rama.entity.Revision;
import org.rama.repository.revision.RevisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.*;

public class RevisionService {
    private static final Logger log = LoggerFactory.getLogger(RevisionService.class);

    private final RevisionRepository revisionRepository;
    private final ObjectMapper objectMapper;
    private final RevisionClickHouseRepository clickHouseRepository; // nullable

    public RevisionService(RevisionRepository revisionRepository) {
        this(revisionRepository, JsonMapper.builder().build(), null);
    }

    public RevisionService(RevisionRepository revisionRepository, ObjectMapper objectMapper) {
        this(revisionRepository, objectMapper, null);
    }

    public RevisionService(RevisionRepository revisionRepository,
                           ObjectMapper objectMapper,
                           RevisionClickHouseRepository clickHouseRepository) {
        this.revisionRepository = revisionRepository;
        this.objectMapper = objectMapper;
        this.clickHouseRepository = clickHouseRepository;
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

    /**
     * Return the latest revision at or before {@code at} for the given key.
     * <p>When a {@link RevisionClickHouseRepository} is configured, the query is dispatched
     * there first. Any {@link RuntimeException} (e.g. ClickHouse unavailable) is caught,
     * logged at WARN, and the method falls back to the JPA repository so audit reads keep
     * working even when the analytical store is down.
     */
    public Optional<Revision> getStateAt(String revisionKey, OffsetDateTime at) {
        if (clickHouseRepository != null) {
            try {
                Optional<ClickHouseRevisionRecord> chResult = clickHouseRepository.getStateAt(revisionKey, at);
                return chResult.map(this::fromClickHouse);
            } catch (RuntimeException e) {
                // Fail-soft: any ClickHouse read error falls back to JPA. Audit reads
                // must keep working even when the analytical store is unavailable.
                log.warn("ClickHouse getStateAt failed; falling back to JPA for key={}, at={}. Cause: {}",
                        revisionKey, at, e.getMessage());
            }
        }
        return revisionRepository
                .findFirstByRevisionKeyAndRevisionDatetimeLessThanEqualOrderByRevisionDatetimeDesc(revisionKey, at);
    }

    @SuppressWarnings("unchecked")
    private Revision fromClickHouse(ClickHouseRevisionRecord r) {
        Revision rev = new Revision();
        // id field in Revision is Long (boxed). Treat ClickHouse 0L (placeholder used by listeners)
        // as null so JPA-style equality stays consistent. Listener rows are also filtered at
        // the repository level (WHERE id > 0), so in practice this null path is unreachable
        // for canonical reads — it remains as defense against direct callers passing id=0.
        rev.setId(r.id() == 0 ? null : r.id());
        rev.setRevisionKey(r.revisionKey());
        rev.setMrn(r.mrn());
        rev.setRevisionEntity(r.revisionEntity());
        rev.setRevisionDatetime(r.revisionDatetime());
        // Deserialize the JSON payloads. On failure or null input, leave the map field
        // unset on the entity — the caller still gets a usable Revision with key/mrn/datetime;
        // the payload is just absent. Note: Revision.revisionData is annotated @NonNull
        // (Lombok generates a setter that throws on null), so we MUST skip the setter
        // when parse returns null.
        Map<String, Object> data = parseJsonMap(r.revisionData(), r.revisionKey(), "revision_data");
        if (data != null) rev.setRevisionData(data);
        rev.setRevisionChange(parseJsonMap(r.revisionChange(), r.revisionKey(), "revision_change"));
        return rev;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json, String contextKey, String column) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (RuntimeException e) {
            log.warn("Failed to deserialize ClickHouse {} for revisionKey={}; returning null. Cause: {}",
                    column, contextKey, e.getMessage());
            return null;
        }
    }

    /**
     * Build a ClickHouse record from the same fields used to persist the SQL Revision.
     * Returns null on serialization failure — the SQL write remains the source of truth
     * and a structured log warning is emitted by the caller.
     */
    public ClickHouseRevisionRecord toClickHouseRecord(
            long sqlId, String revisionKey, String revisionEntity,
            Map<String, Object> revisionData, Map<String, Object> revisionChange) {
        try {
            String dataJson = objectMapper.writeValueAsString(revisionData);
            String changeJson = revisionChange == null ? null : objectMapper.writeValueAsString(revisionChange);
            String mrn = revisionData != null && revisionData.get("mrn") != null
                    ? java.util.Objects.toString(revisionData.get("mrn"), null) : null;
            return ClickHouseRevisionRecord.of(
                    sqlId, revisionKey, mrn, revisionEntity,
                    java.time.OffsetDateTime.now(), dataJson, changeJson,
                    null, null, null, null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

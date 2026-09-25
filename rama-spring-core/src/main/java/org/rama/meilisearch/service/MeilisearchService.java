package org.rama.meilisearch.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.Searchable;
import com.meilisearch.sdk.model.Task;
import com.meilisearch.sdk.model.TaskInfo;
import com.meilisearch.sdk.model.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.rama.annotation.SyncToMeilisearch;
import org.rama.meilisearch.EnsuredMeilisearchIndexes;
import org.rama.meilisearch.MeilisearchIndexSettings;
import org.rama.meilisearch.MeilisearchIndexSettingsApplier;
import org.rama.meilisearch.MeilisearchIndexSettingsResolver;
import org.rama.meilisearch.MeilisearchIndexes;
import org.rama.meilisearch.mapper.IMeilisearchMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class MeilisearchService {
    private final ApplicationContext context;
    private final Client meilisearchClient;
    private final JsonMapper objectMapper;
    private final MeilisearchErrorHandler errorHandler;
    private final List<MeilisearchIndexSettingsResolver> settingsResolvers;
    private final EnsuredMeilisearchIndexes ensuredIndexes;

    public MeilisearchService(ApplicationContext context, Client meilisearchClient, JsonMapper objectMapper, MeilisearchErrorHandler errorHandler) {
        this(context, meilisearchClient, objectMapper, errorHandler, List.of(), new EnsuredMeilisearchIndexes());
    }

    public MeilisearchService(ApplicationContext context, Client meilisearchClient, JsonMapper objectMapper, MeilisearchErrorHandler errorHandler,
                               List<MeilisearchIndexSettingsResolver> settingsResolvers, EnsuredMeilisearchIndexes ensuredIndexes) {
        this.context = context;
        this.meilisearchClient = meilisearchClient;
        this.objectMapper = objectMapper;
        this.errorHandler = errorHandler;
        this.settingsResolvers = settingsResolvers;
        this.ensuredIndexes = ensuredIndexes;
    }

    @Async
    public <T> void sync(T entity) {
        try {
            String splitFieldValue = readSplitFieldValue(entity);
            String indexName = indexNameFor(entity.getClass(), splitFieldValue);
            if (splitFieldValue != null) {
                ensureSplitIndexInitialized(entity.getClass(), indexName, splitFieldValue);
            }
            TaskInfo taskInfo = addDocuments(indexName, entity);
            meilisearchClient.waitForTask(taskInfo.getTaskUid());
            Task task = meilisearchClient.getTask(taskInfo.getTaskUid());
            if (task != null && task.getStatus() == TaskStatus.FAILED) {
                errorHandler.handleTaskFailure(taskInfo, task, entity);
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /** The index a specific entity instance belongs to -- the same as
     * {@link #resolveIndexName(Class)} for a plain entity, or that plus the entity's own
     * (sanitized) {@code @SyncToMeilisearch(indexNameField = ...)} field value for a split one. */
    public String resolveIndexName(Object entity) {
        return indexNameFor(entity.getClass(), readSplitFieldValue(entity));
    }

    /** The named field's raw value off {@code entity}, or {@code null} for a plain (unsplit)
     * entity -- {@code entity.getClass()}'s annotation names no {@code indexNameField}, or names
     * one that's currently {@code null} on this particular instance. */
    private String readSplitFieldValue(Object entity) {
        SyncToMeilisearch annotation = entity.getClass().getAnnotation(SyncToMeilisearch.class);
        if (annotation == null || annotation.indexNameField().isEmpty()) {
            return null;
        }
        String fieldName = annotation.indexNameField();
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(entity);
            return value == null ? null : value.toString();
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new IllegalStateException("@SyncToMeilisearch(indexNameField = \"" + fieldName
                    + "\") on " + entity.getClass().getSimpleName() + " does not name a readable field", ex);
        }
    }

    /** The index a specific ({@code entityClass}, {@code splitFieldValue}) pair resolves to --
     * lets a caller that hasn't got an entity instance in hand (e.g.
     * {@code MeilisearchIndexInitializer}, eagerly initializing every
     * {@link MeilisearchIndexSettingsResolver}-named index at startup) compute the same index name
     * {@link #resolveIndexName(Object)} would for a matching instance. */
    public String resolveIndexName(Class<?> entityClass, String splitFieldValue) {
        return indexNameFor(entityClass, splitFieldValue);
    }

    private String indexNameFor(Class<?> entityClass, String splitFieldValue) {
        String base = resolveIndexName(entityClass);
        return splitFieldValue == null ? base : base + "_" + sanitize(splitFieldValue);
    }

    /** Ensures the split index named {@code indexName} exists and carries the right settings; a
     * no-op past the first call for any given index name -- either because
     * {@code MeilisearchIndexInitializer} already ensured it eagerly at startup (any split index a
     * {@link MeilisearchIndexSettingsResolver} bean names), or because an earlier sync of this
     * same value already did. */
    private void ensureSplitIndexInitialized(Class<?> entityClass, String indexName, String splitFieldValue) throws MeilisearchException {
        if (!ensuredIndexes.markEnsured(indexName)) {
            return;
        }
        SyncToMeilisearch annotation = entityClass.getAnnotation(SyncToMeilisearch.class);
        String primaryKey = resolvePrimaryKey(entityClass);
        Index index = MeilisearchIndexes.getOrCreate(meilisearchClient, indexName, primaryKey);
        MeilisearchIndexSettings settings = resolveSplitIndexSettings(entityClass, annotation, splitFieldValue);
        MeilisearchIndexSettingsApplier.apply(index, settings);
    }

    private MeilisearchIndexSettings resolveSplitIndexSettings(Class<?> entityClass, SyncToMeilisearch annotation, String splitFieldValue) {
        MeilisearchIndexSettings base = MeilisearchIndexSettings.fromAnnotation(annotation);
        return settingsResolvers.stream()
                .filter(r -> r.entityClass() == entityClass && r.splitFieldValue().equals(splitFieldValue))
                .findFirst()
                // Layered, not replaced: a resolver overriding just synonyms for one split value
                // must not silently drop filterableAttributes (or anything else) the annotation
                // declares for every index of this entity -- see MeilisearchIndexSettings#layeredOver.
                .map(r -> r.settings().layeredOver(base))
                .orElse(base);
    }

    public <T> TaskInfo addDocuments(String indexName, T entity) throws Exception {
        Index index = meilisearchClient.index(indexName);
        String json = objectMapper.writeValueAsString(prepareEntityMap(entity));
        return index.addDocuments(json);
    }

    public <T> TaskInfo addDocuments(String indexName, List<T> entities) throws Exception {
        Index index = meilisearchClient.index(indexName);
        String json = objectMapper.writeValueAsString(entities.stream().map(this::prepareEntityMap).toList());
        return index.addDocuments(json);
    }

    public TaskInfo deleteDocument(String indexName, String id) throws MeilisearchException {
        return meilisearchClient.index(indexName).deleteDocument(id);
    }

    public TaskInfo deleteAllDocuments(String indexName) throws MeilisearchException {
        return meilisearchClient.index(indexName).deleteAllDocuments();
    }

    public ArrayList<HashMap<String, Object>> search(String indexName, String query) throws MeilisearchException {
        return meilisearchClient.index(indexName).search(query).getHits();
    }

    public ArrayList<HashMap<String, Object>> search(String indexName, SearchRequest searchRequest) throws MeilisearchException {
        return meilisearchClient.index(indexName).search(searchRequest).getHits();
    }

    /** Only correct for a plain (unsplit) entity -- a split entity (see
     * {@link SyncToMeilisearch#indexNameField()}) has no single index to search across every
     * split-field value; use {@link #search(Class, String, SearchRequest)} instead. */
    public <T> ArrayList<HashMap<String, Object>> search(Class<T> clazz, SearchRequest searchRequest) throws MeilisearchException {
        return searchIndex(resolveIndexName(clazz), resolvePrimaryKey(clazz), searchRequest);
    }

    /** Searches the one split index that {@code splitFieldValue} resolves to -- see
     * {@link SyncToMeilisearch#indexNameField()}. */
    public <T> ArrayList<HashMap<String, Object>> search(Class<T> clazz, String splitFieldValue, SearchRequest searchRequest) throws MeilisearchException {
        return searchIndex(indexNameFor(clazz, splitFieldValue), resolvePrimaryKey(clazz), searchRequest);
    }

    @SuppressWarnings("unchecked")
    private ArrayList<HashMap<String, Object>> searchIndex(String indexName, String key, SearchRequest searchRequest) throws MeilisearchException {
        Searchable searchable = meilisearchClient.index(indexName).search(searchRequest);
        ArrayList<HashMap<String, Object>> hits = searchable.getHits();
        for (HashMap<String, Object> hit : hits) {
            if (hit.containsKey("_formatted")) {
                Map<String, Object> formatted = (Map<String, Object>) hit.get("_formatted");
                if (formatted.containsKey(key + "_original")) {
                    formatted.put(key, formatted.get(key + "_original"));
                }
                formatted.remove(key + "_original");
            }
            if (hit.containsKey(key + "_original")) {
                hit.put(key, hit.get(key + "_original"));
            }
            hit.remove(key + "_original");
        }
        return hits;
    }

    public <T> List<T> searchConvert(Class<T> clazz, SearchRequest searchRequest) throws MeilisearchException {
        return search(clazz, searchRequest).stream().map(hit -> objectMapper.convertValue(hit, clazz)).toList();
    }

    private <T> Map<String, Object> prepareEntityMap(T entity) {
        if (entity == null) {
            return new HashMap<>();
        }
        try {
            SyncToMeilisearch annotation = entity.getClass().getAnnotation(SyncToMeilisearch.class);
            Map<String, Object> map;
            if (annotation != null) {
                IMeilisearchMapper mapper = context.getBean(annotation.mapperClass());
                map = mapper.convert(entity);
            } else {
                map = objectMapper.convertValue(entity, new TypeReference<>() {});
            }
            return sanitizeMap(map, resolvePrimaryKey(entity.getClass()));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to sanitize entity", ex);
        }
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) {
            return map;
        }
        Object value = map.get(key);
        if (value instanceof String original) {
            String sanitized = sanitize(original);
            if (!sanitized.equals(original)) {
                map.put(key, sanitized);
            }
            map.put(key + "_original", original);
        }
        return map;
    }

    private String sanitize(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("ID cannot be null or empty");
        }
        String sanitized = key.replaceAll("[^a-zA-Z0-9_-]", "_");
        return sanitized.equals(key) ? sanitized : sanitized + "_" + shortHash(key);
    }

    private String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException("SHA-256 not available", ex);
        }
    }

    public String resolveIndexName(Class<?> clazz) {
        SyncToMeilisearch annotation = clazz.getAnnotation(SyncToMeilisearch.class);
        return annotation != null && !annotation.indexName().isEmpty() ? annotation.indexName() : clazz.getSimpleName().toLowerCase();
    }

    public String resolvePrimaryKey(Class<?> clazz) {
        SyncToMeilisearch annotation = clazz.getAnnotation(SyncToMeilisearch.class);
        String defaultPrimaryKey = annotation == null ? "" : annotation.primaryKey();
        if (!defaultPrimaryKey.isEmpty() && !"id".equals(defaultPrimaryKey)) {
            return defaultPrimaryKey;
        }
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(jakarta.persistence.Id.class) || field.isAnnotationPresent(org.springframework.data.annotation.Id.class)) {
                return field.getName();
            }
        }
        return defaultPrimaryKey.isEmpty() ? "id" : defaultPrimaryKey;
    }
}

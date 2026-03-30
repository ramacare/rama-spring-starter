package org.rama.meilisearch.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;
    private final MeilisearchErrorHandler errorHandler;

    public MeilisearchService(ApplicationContext context, Client meilisearchClient, ObjectMapper objectMapper, MeilisearchErrorHandler errorHandler) {
        this.context = context;
        this.meilisearchClient = meilisearchClient;
        this.objectMapper = objectMapper;
        this.errorHandler = errorHandler;
    }

    @Async
    public <T> void sync(T entity) {
        try {
            delayRetrieveTaskInfo(addDocuments(resolveIndexName(entity.getClass()), entity), entity);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Async
    public <T> void delayRetrieveTaskInfo(TaskInfo taskInfo, T entity) {
        meilisearchClient.waitForTask(taskInfo.getTaskUid());
        Task task = meilisearchClient.getTask(taskInfo.getTaskUid());
        if (task != null && task.getStatus() == TaskStatus.FAILED) {
            errorHandler.handleTaskFailure(taskInfo, task, entity);
        }
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

    @SuppressWarnings("unchecked")
    public <T> ArrayList<HashMap<String, Object>> search(Class<T> clazz, SearchRequest searchRequest) throws MeilisearchException {
        String indexName = resolveIndexName(clazz);
        String key = resolvePrimaryKey(clazz);
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

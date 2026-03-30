package org.rama.mongo.indexing;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class DeferredIndexManager {
    private static final int INDEX_TRIGGER_THRESHOLD = 100;

    private final Map<String, Set<LinkedHashMap<String, Sort.Direction>>> indexPools = new ConcurrentHashMap<>();
    private final Map<String, Map<LinkedHashMap<String, Sort.Direction>, Integer>> fieldUsageMap = new ConcurrentHashMap<>();
    private final MongoTemplate mongoTemplate;

    public DeferredIndexManager(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void trackFields(String collectionName, LinkedHashMap<String, Sort.Direction> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        indexPools.computeIfAbsent(collectionName, key -> new HashSet<>()).add(new LinkedHashMap<>(fields));
        fieldUsageMap.computeIfAbsent(collectionName, key -> new HashMap<>()).merge(fields, 1, Integer::sum);
    }

    @PreDestroy
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void autoFlushIndexes() {
        for (String collection : fieldUsageMap.keySet()) {
            Map<LinkedHashMap<String, Sort.Direction>, Integer> fieldCounts = fieldUsageMap.getOrDefault(collection, Collections.emptyMap());
            List<IndexInfo> existingIndexes = mongoTemplate.indexOps(collection).getIndexInfo();

            for (Map.Entry<LinkedHashMap<String, Sort.Direction>, Integer> entry : fieldCounts.entrySet()) {
                LinkedHashMap<String, Sort.Direction> fields = entry.getKey();
                if (entry.getValue() >= INDEX_TRIGGER_THRESHOLD && indexNotExists(existingIndexes, fields)) {
                    Index index = new Index().named(buildIndexName(fields));
                    fields.forEach(index::on);
                    mongoTemplate.indexOps(collection).createIndex(index);
                    indexPools.getOrDefault(collection, Collections.emptySet()).remove(fields);
                }
            }
        }
        fieldUsageMap.clear();
    }

    public void forceCreateAll() {
        for (String collection : new HashSet<>(indexPools.keySet())) {
            forceCreateIndexes(collection);
        }
        fieldUsageMap.clear();
    }

    public void forceCreateIndexes(String collectionName) {
        Set<LinkedHashMap<String, Sort.Direction>> fieldSets = indexPools.getOrDefault(collectionName, Collections.emptySet());
        if (fieldSets.isEmpty()) {
            return;
        }
        List<IndexInfo> existingIndexes = mongoTemplate.indexOps(collectionName).getIndexInfo();
        for (LinkedHashMap<String, Sort.Direction> fields : fieldSets) {
            if (indexNotExists(existingIndexes, fields)) {
                Index index = new Index().named(buildIndexName(fields));
                fields.forEach(index::on);
                mongoTemplate.indexOps(collectionName).createIndex(index);
            }
        }
        indexPools.remove(collectionName);
    }

    private boolean indexNotExists(List<IndexInfo> existingIndexes, LinkedHashMap<String, Sort.Direction> targetFields) {
        for (IndexInfo existing : existingIndexes) {
            List<IndexField> indexFields = existing.getIndexFields();
            if (indexFields.size() != targetFields.size()) {
                continue;
            }
            boolean matches = true;
            int i = 0;
            for (Map.Entry<String, Sort.Direction> entry : targetFields.entrySet()) {
                IndexField existingField = indexFields.get(i++);
                if (!existingField.getKey().equals(entry.getKey())) {
                    matches = false;
                    break;
                }
                if (existingField.getDirection() != null && !existingField.getDirection().equals(entry.getValue())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return false;
            }
        }
        return true;
    }

    private String buildIndexName(LinkedHashMap<String, Sort.Direction> fields) {
        String rawName = fields.entrySet().stream()
                .map(entry -> entry.getKey() + "_" + entry.getValue().name().toLowerCase())
                .collect(Collectors.joining("_"));
        if (rawName.length() <= 127) {
            return rawName;
        }
        return rawName.substring(0, 90) + "_idx_" + Integer.toHexString(rawName.hashCode());
    }
}

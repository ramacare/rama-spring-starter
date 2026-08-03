package org.rama.mongo.indexing;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class DeferredIndexManager {
    public static final int DEFAULT_INDEX_TRIGGER_THRESHOLD = 100;
    public static final long DEFAULT_FLUSH_INTERVAL_MS = 10 * 60 * 1000L;

    private final Map<String, Set<LinkedHashMap<String, Sort.Direction>>> indexPools = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentHashMap<LinkedHashMap<String, Sort.Direction>, Integer>> fieldUsageMap = new ConcurrentHashMap<>();
    private final MongoTemplate mongoTemplate;
    private final int indexTriggerThreshold;
    private final long flushIntervalMs;

    private ScheduledExecutorService scheduler;

    public DeferredIndexManager(MongoTemplate mongoTemplate) {
        this(mongoTemplate, DEFAULT_INDEX_TRIGGER_THRESHOLD, DEFAULT_FLUSH_INTERVAL_MS);
    }

    public DeferredIndexManager(MongoTemplate mongoTemplate, int indexTriggerThreshold, long flushIntervalMs) {
        this.mongoTemplate = mongoTemplate;
        this.indexTriggerThreshold = indexTriggerThreshold;
        this.flushIntervalMs = flushIntervalMs;
    }

    /**
     * The flush is scheduled on a thread this bean owns rather than via {@code @Scheduled},
     * which would only fire if the consuming application happened to declare
     * {@code @EnableScheduling} -- the starter itself only enables async. See starter#34.
     */
    @PostConstruct
    void startScheduler() {
        if (flushIntervalMs <= 0) {
            log.info("Deferred Mongo index flush disabled (interval {} ms)", flushIntervalMs);
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rama-deferred-index");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::flushQuietly, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        flushQuietly();
    }

    public void trackFields(String collectionName, LinkedHashMap<String, Sort.Direction> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        indexPools.computeIfAbsent(collectionName, key -> ConcurrentHashMap.newKeySet()).add(new LinkedHashMap<>(fields));
        fieldUsageMap.computeIfAbsent(collectionName, key -> new ConcurrentHashMap<>()).merge(fields, 1, Integer::sum);
    }

    private void flushQuietly() {
        try {
            autoFlushIndexes();
        } catch (RuntimeException ex) {
            // A transient Mongo outage must not kill the scheduler: scheduleWithFixedDelay
            // cancels all further runs once the task throws.
            log.warn("Deferred Mongo index flush failed; will retry on the next interval", ex);
        }
    }

    /**
     * Usage counts <em>accumulate</em> across flushes. Only counters that have been acted on --
     * index created, or found already present -- are dropped. Clearing every counter each run
     * meant a field-set queried steadily but below the threshold within a single window never
     * reached it, however long the application ran. See starter#34.
     */
    public void autoFlushIndexes() {
        for (Map.Entry<String, ConcurrentHashMap<LinkedHashMap<String, Sort.Direction>, Integer>> collectionEntry : fieldUsageMap.entrySet()) {
            String collection = collectionEntry.getKey();
            ConcurrentHashMap<LinkedHashMap<String, Sort.Direction>, Integer> fieldCounts = collectionEntry.getValue();
            if (fieldCounts.isEmpty()) {
                continue;
            }

            List<IndexInfo> existingIndexes = null;
            for (Map.Entry<LinkedHashMap<String, Sort.Direction>, Integer> entry : fieldCounts.entrySet()) {
                if (entry.getValue() < indexTriggerThreshold) {
                    continue;
                }
                if (existingIndexes == null) {
                    existingIndexes = mongoTemplate.indexOps(collection).getIndexInfo();
                }
                LinkedHashMap<String, Sort.Direction> fields = entry.getKey();
                if (indexNotExists(existingIndexes, fields)) {
                    Index index = new Index().named(buildIndexName(fields));
                    fields.forEach(index::on);
                    mongoTemplate.indexOps(collection).createIndex(index);
                    log.info("Created deferred Mongo index {} on {} after {} uses", index.getIndexKeys().keySet(), collection, entry.getValue());
                }
                fieldCounts.remove(fields);
                indexPools.getOrDefault(collection, Collections.emptySet()).remove(fields);
            }
        }
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
        fieldUsageMap.remove(collectionName);
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

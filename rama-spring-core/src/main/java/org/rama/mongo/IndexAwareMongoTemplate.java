package org.rama.mongo;

import org.jspecify.annotations.NonNull;
import org.rama.mongo.indexing.DeferredIndexManager;
import org.rama.mongo.indexing.IndexFieldExtractor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.query.Query;

import java.util.LinkedHashMap;
import java.util.List;

public class IndexAwareMongoTemplate extends MongoTemplate {
    private final DeferredIndexManager indexManager;

    public IndexAwareMongoTemplate(MongoDatabaseFactory dbFactory, MongoConverter converter, DeferredIndexManager indexManager) {
        super(dbFactory, converter);
        this.indexManager = indexManager;
    }

    @Override
    public <T> @NonNull List<T> find(@NonNull Query query, @NonNull Class<T> entityClass, @NonNull String collectionName) {
        trackFieldsFromQuery(query, collectionName);
        return super.find(query, entityClass, collectionName);
    }

    @Override
    public <T> @NonNull List<T> find(@NonNull Query query, @NonNull Class<T> entityClass) {
        trackFieldsFromQuery(query, super.getCollectionName(entityClass));
        return super.find(query, entityClass);
    }

    @Override
    public long count(@NonNull Query query, Class<?> entityClass, @NonNull String collectionName) {
        trackFieldsFromQuery(query, collectionName);
        return super.count(query, entityClass, collectionName);
    }

    @Override
    public long count(@NonNull Query query, @NonNull Class<?> entityClass) {
        trackFieldsFromQuery(query, super.getCollectionName(entityClass));
        return super.count(query, entityClass);
    }

    @Override
    public <T> T findOne(@NonNull Query query, @NonNull Class<T> entityClass, @NonNull String collectionName) {
        trackFieldsFromQuery(query, collectionName);
        return super.findOne(query, entityClass, collectionName);
    }

    @Override
    public <T> T findOne(@NonNull Query query, @NonNull Class<T> entityClass) {
        trackFieldsFromQuery(query, super.getCollectionName(entityClass));
        return super.findOne(query, entityClass);
    }

    private void trackFieldsFromQuery(Query query, String collectionName) {
        LinkedHashMap<String, Sort.Direction> ordered = IndexFieldExtractor.extractOptimizedIndexFields(query);
        indexManager.trackFields(collectionName, ordered);
    }
}

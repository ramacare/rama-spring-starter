package org.rama.mongo.service;

import jakarta.persistence.Id;
import org.rama.annotation.SyncToMongo;
import org.rama.annotation.TransformableMap;
import org.rama.mongo.mapper.IMongoMapper;
import org.rama.mongo.service.transformer.DateTransformer;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MongoSyncService {
    private final ApplicationContext context;
    private final MongoTemplate mongoTemplate;

    public MongoSyncService(ApplicationContext context, MongoTemplate mongoTemplate) {
        this.context = context;
        this.mongoTemplate = mongoTemplate;
    }

    @Async
    @SuppressWarnings("unchecked")
    public void sync(Object entity) {
        Class<?> entityClass = entity.getClass();
        if (!entityClass.isAnnotationPresent(SyncToMongo.class)) {
            throw new IllegalArgumentException("Missing @SyncToMongo on " + entityClass.getName());
        }

        SyncToMongo syncMeta = entityClass.getAnnotation(SyncToMongo.class);
        Class<?> documentClass = syncMeta.mongoClass();
        IMongoMapper<Object, Object> mapper = (IMongoMapper<Object, Object>) context.getBean(syncMeta.mapperClass());

        Field idField = findIdField(entityClass);
        idField.setAccessible(true);
        Object id;
        try {
            id = idField.get(entity);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException("Cannot access ID field", ex);
        }

        Object mongoDoc = "id".equalsIgnoreCase(idField.getName())
                ? mongoTemplate.findById(id, documentClass)
                : mongoTemplate.find(new Query(Criteria.where(idField.getName()).is(id)), documentClass)
                .stream()
                .findFirst()
                .orElse(null);

        if (mongoDoc != null) {
            mapper.updateMongoEntity(entity, mongoDoc);
        } else {
            mongoDoc = mapper.newMongoEntity(entity);
        }

        applyDateTransform(mongoDoc);
        mongoTemplate.save(mongoDoc);
    }

    private Field findIdField(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        throw new IllegalStateException("No @Id field found");
    }

    @SuppressWarnings("unchecked")
    private void applyDateTransform(Object doc) {
        List<Field> fields = new ArrayList<>();
        Class<?> clazz = doc.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(TransformableMap.class) && Map.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            clazz = clazz.getSuperclass();
        }
        for (Field field : fields) {
            try {
                Map<String, Object> map = (Map<String, Object>) field.get(doc);
                if (map != null) {
                    field.set(doc, DateTransformer.transform(map));
                }
            } catch (IllegalAccessException ex) {
                throw new RuntimeException("Cannot transform field: " + field.getName(), ex);
            }
        }
    }
}

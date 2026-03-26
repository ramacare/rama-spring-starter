package org.rama.starter.service;

import org.rama.starter.entity.PageableDTO;
import org.rama.starter.entity.PageableInput;
import org.rama.starter.mongo.IndexAwareMongoTemplate;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

public class GenericMongoService {
    private final IndexAwareMongoTemplate mongoTemplate;

    public GenericMongoService(IndexAwareMongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public <T> List<T> findEntity(Class<T> entityClass, Criteria criteria) {
        return mongoTemplate.find(new Query(criteria), entityClass);
    }

    public <T> PageableDTO<T> findEntityPageable(Class<T> entityClass, PageableInput pageable, Criteria criteria) {
        Query query = new Query(criteria).with(pageable.toPageRequest());
        List<T> result = mongoTemplate.find(query, entityClass);
        long count = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), entityClass);
        return PageableDTO.of(new PageImpl<>(result, pageable.toPageRequest(), count));
    }

    public <T> PageableDTO<T> findEntityIdPageable(Class<T> entityClass, PageableInput pageable, Criteria criteria) {
        Query query = new Query(criteria).with(pageable.toPageRequest());
        query.fields().include("_id");
        List<T> result = mongoTemplate.find(query, entityClass);
        long count = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), entityClass);
        return PageableDTO.of(new PageImpl<>(result, pageable.toPageRequest(), count));
    }

    public <T> T findEntityIdLatest(Class<T> entityClass, Criteria criteria) {
        Query query = new Query(criteria).with(Sort.by(Sort.Direction.DESC, "_id")).limit(1);
        query.fields().include("_id");
        List<T> result = mongoTemplate.find(query, entityClass);
        return result.isEmpty() ? null : result.get(0);
    }
}

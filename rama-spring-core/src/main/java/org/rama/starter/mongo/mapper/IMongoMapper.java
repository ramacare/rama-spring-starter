package org.rama.starter.mongo.mapper;

public interface IMongoMapper<T, U> {
    U newMongoEntity(T entity);
    void updateMongoEntity(T entity, U update);
}

package org.rama.mongo.mapper;

import org.mapstruct.MappingTarget;

public interface IMongoMapper<T, U> {
    U newMongoEntity(T entity);
    void updateMongoEntity(T entity, @MappingTarget U update);
}

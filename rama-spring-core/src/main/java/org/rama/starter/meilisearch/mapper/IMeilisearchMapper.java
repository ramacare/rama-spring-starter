package org.rama.starter.meilisearch.mapper;

import java.util.List;
import java.util.Map;

public interface IMeilisearchMapper {
    Map<String, Object> convert(Object entity);

    default List<Map<String, Object>> convert(List<Object> entities) {
        return entities.stream().map(this::convert).toList();
    }
}

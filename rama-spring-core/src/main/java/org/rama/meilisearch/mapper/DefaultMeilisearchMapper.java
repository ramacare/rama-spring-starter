package org.rama.meilisearch.mapper;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

public class DefaultMeilisearchMapper implements IMeilisearchMapper {
    private final JsonMapper objectMapper;

    public DefaultMeilisearchMapper(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> convert(Object entity) {
        return objectMapper.convertValue(entity, new TypeReference<>() {});
    }
}

package org.rama.meilisearch.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class DefaultMeilisearchMapper implements IMeilisearchMapper {
    private final ObjectMapper objectMapper;

    public DefaultMeilisearchMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> convert(Object entity) {
        return objectMapper.convertValue(entity, new TypeReference<>() {});
    }
}

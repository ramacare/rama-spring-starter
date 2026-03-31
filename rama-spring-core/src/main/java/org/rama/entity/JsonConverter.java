package org.rama.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Converter
public class JsonConverter implements AttributeConverter<Object, String> {
    private static final Logger log = LoggerFactory.getLogger(JsonConverter.class);
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    public static ObjectMapper createObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public String convertToDatabaseColumn(Object attribute) {
        try {
            return attribute == null ? null : OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException ex) {
            log.error("JsonConverter serialization failed for type {}: {}", attribute.getClass().getName(), ex.getMessage());
            return null;
        }
    }

    @Override
    public Object convertToEntityAttribute(String dbData) {
        try {
            return dbData == null ? null : OBJECT_MAPPER.readValue(dbData, Object.class);
        } catch (JsonProcessingException ex) {
            log.error("JsonConverter deserialization failed: {}", ex.getMessage());
            return null;
        }
    }
}

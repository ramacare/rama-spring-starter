package org.rama.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class JsonConverter implements AttributeConverter<Object, String> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Override
    public String convertToDatabaseColumn(Object attribute) {
        try {
            return attribute == null ? null : OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    @Override
    public Object convertToEntityAttribute(String dbData) {
        try {
            return dbData == null ? null : OBJECT_MAPPER.readValue(dbData, Object.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}

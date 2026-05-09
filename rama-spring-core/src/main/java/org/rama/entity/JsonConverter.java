package org.rama.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;

@Converter
public class JsonConverter implements AttributeConverter<Object, String> {
    private static final Logger log = LoggerFactory.getLogger(JsonConverter.class);
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    public static ObjectMapper createObjectMapper() {
        // Jackson 3 merged jsr310 (java.time) support into jackson-databind and dropped
        // SerializationFeature.WRITE_DATES_AS_TIMESTAMPS — ISO-8601 is now the default,
        // so no JavaTimeModule registration is needed.
        return JsonMapper.builder()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Preserve the Jackson 2 default that quietly coerces JSON strings
                // ("1") into numeric / boolean fields. Jackson 3 fails by default;
                // consumer JSON columns rely on the lenient behaviour.
                .withCoercionConfigDefaults(cfg ->
                        cfg.setCoercion(CoercionInputShape.String, CoercionAction.TryConvert))
                .build();
    }

    @Override
    public String convertToDatabaseColumn(Object attribute) {
        try {
            return attribute == null ? null : OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (RuntimeException ex) {
            log.error("JsonConverter serialization failed for type {}: {}", attribute.getClass().getName(), ex.getMessage());
            return null;
        }
    }

    @Override
    public Object convertToEntityAttribute(String dbData) {
        try {
            return dbData == null ? null : OBJECT_MAPPER.readValue(dbData, Object.class);
        } catch (RuntimeException ex) {
            log.error("JsonConverter deserialization failed: {}", ex.getMessage());
            return null;
        }
    }
}

package org.rama.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.rama.util.EncryptionUtil;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;

import java.util.TimeZone;

@Converter
public class JsonEncryptConverter implements AttributeConverter<Object, String> {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Same frame as JsonConverter: an encrypted JSON column is still a JSON
            // column, and must not round-trip datetimes through a different zone than
            // its unencrypted counterpart. See starter#39.
            .defaultTimeZone(TimeZone.getDefault())
            .withCoercionConfigDefaults(cfg ->
                    cfg.setCoercion(CoercionInputShape.String, CoercionAction.TryConvert))
            .build();

    @Override
    public String convertToDatabaseColumn(Object attribute) {
        try {
            if (attribute == null) {
                return null;
            }
            String json = OBJECT_MAPPER.writeValueAsString(attribute);
            return json.isEmpty() ? json : EncryptionUtil.encrypt(json);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public Object convertToEntityAttribute(String dbData) {
        try {
            if (dbData == null) {
                return null;
            }
            if (dbData.isEmpty()) {
                return dbData;
            }
            return OBJECT_MAPPER.readValue(EncryptionUtil.decrypt(dbData), Object.class);
        } catch (Exception ex) {
            return null;
        }
    }
}

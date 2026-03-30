package org.rama.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.rama.util.EncryptionUtil;

@Converter
public class JsonEncryptConverter implements AttributeConverter<Object, String> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Override
    public String convertToDatabaseColumn(Object attribute) {
        try {
            if (attribute == null) {
                return null;
            }
            String json = OBJECT_MAPPER.writeValueAsString(attribute);
            return json.isEmpty() ? json : EncryptionUtil.encrypt(json);
        } catch (JsonProcessingException ex) {
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

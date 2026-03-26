package org.rama.starter.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.rama.starter.crypto.NoOpTextEncryptor;
import org.rama.starter.crypto.TextEncryptor;

@Converter
public class JsonEncryptConverter implements AttributeConverter<Object, String> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static volatile TextEncryptor textEncryptor = new NoOpTextEncryptor();

    public static void setTextEncryptor(TextEncryptor encryptor) {
        textEncryptor = encryptor == null ? new NoOpTextEncryptor() : encryptor;
    }

    @Override
    public String convertToDatabaseColumn(Object attribute) {
        try {
            if (attribute == null) {
                return null;
            }
            String json = OBJECT_MAPPER.writeValueAsString(attribute);
            return json.isEmpty() ? json : textEncryptor.encrypt(json);
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
            return OBJECT_MAPPER.readValue(textEncryptor.decrypt(dbData), Object.class);
        } catch (Exception ex) {
            return null;
        }
    }
}

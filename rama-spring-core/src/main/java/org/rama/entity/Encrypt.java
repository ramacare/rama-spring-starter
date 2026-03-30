package org.rama.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.rama.util.EncryptionUtil;

@Converter
public class Encrypt implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : EncryptionUtil.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : EncryptionUtil.decrypt(dbData);
    }
}

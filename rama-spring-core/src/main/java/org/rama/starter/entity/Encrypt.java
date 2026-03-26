package org.rama.starter.entity;

import jakarta.persistence.AttributeConverter;
import org.rama.starter.crypto.NoOpTextEncryptor;
import org.rama.starter.crypto.TextEncryptor;

public class Encrypt implements AttributeConverter<String, String> {
    private static volatile TextEncryptor textEncryptor = new NoOpTextEncryptor();

    public static void setTextEncryptor(TextEncryptor encryptor) {
        textEncryptor = encryptor == null ? new NoOpTextEncryptor() : encryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : textEncryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : textEncryptor.decrypt(dbData);
    }
}

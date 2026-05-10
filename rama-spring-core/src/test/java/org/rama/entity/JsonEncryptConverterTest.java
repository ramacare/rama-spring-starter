package org.rama.entity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.rama.util.EncryptionUtil;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonEncryptConverterTest {

    @BeforeAll
    static void setKey() {
        // 16/24/32-byte key for AES; deterministic across runs so encrypt/decrypt
        // round-trip is reproducible in tests.
        EncryptionUtil.setKey("0123456789abcdef0123456789abcdef");
    }

    private final JsonEncryptConverter converter = new JsonEncryptConverter();

    @Test
    void nullInput_roundTripsToNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void mapPayload_encryptsThenDecrypts() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ssn", "123-45-6789");
        payload.put("score", 42);

        String stored = converter.convertToDatabaseColumn(payload);
        // Encrypted blob should not contain the cleartext.
        assertThat(stored).doesNotContain("123-45-6789");

        Object back = converter.convertToEntityAttribute(stored);
        assertThat(back).isEqualTo(payload);
    }
}

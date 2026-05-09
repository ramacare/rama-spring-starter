package org.rama.entity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonConverterTest {

    private final JsonConverter converter = new JsonConverter();

    @Test
    void nullInput_roundTripsToNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void emptyMap_roundTrips() {
        Map<String, Object> empty = new LinkedHashMap<>();
        String json = converter.convertToDatabaseColumn(empty);
        assertThat(json).isEqualTo("{}");
        Object back = converter.convertToEntityAttribute(json);
        assertThat(back).isInstanceOf(Map.class).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).isEmpty();
    }

    @Test
    void offsetDateTime_serializesAsIso8601String_notNanos() {
        Map<String, Object> payload = new LinkedHashMap<>();
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 9, 10, 30, 0, 0, ZoneOffset.ofHours(7));
        payload.put("recordedAt", now);

        String json = converter.convertToDatabaseColumn(payload);

        assertThat(json).contains("\"recordedAt\":\"2026-05-09T10:30:00+07:00\"");
        assertThat(json).doesNotMatch(".*\"recordedAt\":\\[.*\\].*"); // not array of nanos
    }

    @Test
    void listOfMaps_roundTrips() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("k", 1);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("k", 2);
        List<Map<String, Object>> list = List.of(a, b);

        String json = converter.convertToDatabaseColumn(list);
        Object back = converter.convertToEntityAttribute(json);

        assertThat(back).isEqualTo(list);
    }

    @Test
    void nestedJsonRoundTripsViaObjectMapper() {
        // realistic MasterItem.properties / Encounter.metadata payload
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("priceTier", "VIP");
        payload.put("priority", 3);
        payload.put("active", true);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("level", "high");
        nested.put("retries", 5);
        payload.put("settings", nested);

        String json = converter.convertToDatabaseColumn(payload);
        Object back = converter.convertToEntityAttribute(json);

        assertThat(back).isEqualTo(payload);
    }

    /**
     * Jackson 2 quietly coerces JSON string {@code "1"} into a numeric / boolean
     * field. Jackson 3's default is to fail. {@link JsonConverter} restores the
     * Jackson 2 behaviour via {@code CoercionConfig.TryConvert}; this guards
     * against a regression that would silently break any consumer column whose
     * upstream payload sends scalars as quoted strings (FHIR/Odoo/HL7 messages
     * commonly do).
     */
    @Test
    void stringCoercion_intLongBigDecimalBoolean_succeed() throws Exception {
        ObjectMapper mapper = JsonConverter.createObjectMapper();
        String json = """
                { "intField": "1", "longField": "9999999999",
                  "decimalField": "12.50", "boolField": "true" }
                """;

        Coercible result = mapper.readValue(json, Coercible.class);

        assertThat(result.intField).isEqualTo(1);
        assertThat(result.longField).isEqualTo(9_999_999_999L);
        assertThat(result.decimalField).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(result.boolField).isTrue();
    }

    public static class Coercible {
        public int intField;
        public long longField;
        public BigDecimal decimalField;
        public boolean boolField;
    }
}

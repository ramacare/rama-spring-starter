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

import java.util.TimeZone;

@Converter
public class JsonConverter implements AttributeConverter<Object, String> {
    private static final Logger log = LoggerFactory.getLogger(JsonConverter.class);
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    /**
     * Builds a mapper framed in the JVM's default time zone — the same frame
     * {@link OffsetDateTimeConverter}, {@code DateTimeUtil}, {@code QueryUtil} and
     * {@code MongoDBUtil} already use.
     */
    public static ObjectMapper createObjectMapper() {
        return createObjectMapper(TimeZone.getDefault());
    }

    /**
     * @param defaultTimeZone frame for deserialized date/time values; {@code null} falls
     *                        back to the JVM default. Callers wired through Spring pass
     *                        {@code spring.jackson.time-zone} when the consumer has set it.
     */
    public static ObjectMapper createObjectMapper(TimeZone defaultTimeZone) {
        // Jackson 3 merged jsr310 (java.time) support into jackson-databind and dropped
        // SerializationFeature.WRITE_DATES_AS_TIMESTAMPS — ISO-8601 is now the default,
        // so no JavaTimeModule registration is needed.
        return JsonMapper.builder()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Jackson defaults its context zone to UTC, and ADJUST_DATES_TO_CONTEXT_TIME_ZONE
                // re-frames every incoming value into it. Left unset, a payload sent as
                // +07:00 deserializes as Z: same instant, but toLocalDate()/getHour() then
                // disagree with the caller for everything before 07:00 local. See starter#39.
                .defaultTimeZone(defaultTimeZone != null ? defaultTimeZone : TimeZone.getDefault())
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

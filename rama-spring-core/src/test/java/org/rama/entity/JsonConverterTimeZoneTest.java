package org.rama.entity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson's built-in default time zone is UTC, and
 * {@code DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE} is on by default, so a
 * mapper that never calls {@code defaultTimeZone(..)} re-frames every incoming
 * {@code +07:00} value to {@code Z}. The instant survives, but the wall clock and —
 * for anything before 07:00 local — the calendar date do not, which silently breaks
 * {@code toLocalDate()} / {@code getHour()} in entity listeners and validators.
 *
 * <p>Every other datetime component in the starter ({@link OffsetDateTimeConverter},
 * {@code DateTimeUtil}, {@code QueryUtil}, {@code MongoDBUtil}) frames values in
 * {@link ZoneId#systemDefault()}; these tests pin the mapper to the same frame.
 * See starter#39.
 */
class JsonConverterTimeZoneTest {

    /** The wall clock reported downstream in starter#39: 06:57 Bangkok is 23:57 the previous day in UTC. */
    private static final String EARLY_MORNING_BANGKOK = "2026-08-25T06:57:17+07:00";

    public static class Holder {
        public OffsetDateTime recordedAt;
    }

    @Test
    void defaultMapper_usesJvmTimeZone() {
        ObjectMapper mapper = JsonConverter.createObjectMapper();

        assertThat(mapper.deserializationConfig().getTimeZone()).isEqualTo(TimeZone.getDefault());
        assertThat(mapper.serializationConfig().getTimeZone()).isEqualTo(TimeZone.getDefault());
    }

    @Test
    void deserialize_keepsInstantAndFramesInConfiguredZone() {
        ObjectMapper mapper = JsonConverter.createObjectMapper(TimeZone.getTimeZone("Asia/Bangkok"));

        Holder holder = mapper.readValue("{\"recordedAt\":\"" + EARLY_MORNING_BANGKOK + "\"}", Holder.class);

        assertThat(holder.recordedAt.toInstant()).isEqualTo(Instant.parse("2026-08-24T23:57:17Z"));
        assertThat(holder.recordedAt.getOffset()).isEqualTo(ZoneOffset.ofHours(7));
        assertThat(holder.recordedAt.toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 25));
        assertThat(holder.recordedAt.getHour()).isEqualTo(6);
    }

    /**
     * A payload sent as {@code Z} must land on the same instant and be re-framed into the
     * configured zone, so both wire formats produce an identical in-memory value.
     */
    @Test
    void deserialize_normalizesUtcPayloadIntoConfiguredZone() {
        ObjectMapper mapper = JsonConverter.createObjectMapper(TimeZone.getTimeZone("Asia/Bangkok"));

        Holder holder = mapper.readValue("{\"recordedAt\":\"2026-08-24T23:57:17Z\"}", Holder.class);

        assertThat(holder.recordedAt.getOffset()).isEqualTo(ZoneOffset.ofHours(7));
        assertThat(holder.recordedAt.toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 25));
    }

    /** {@code GenericEntityService.createEntity} routes every mutation input map through this path. */
    @Test
    void convertValue_framesInConfiguredZone() {
        ObjectMapper mapper = JsonConverter.createObjectMapper(TimeZone.getTimeZone("Asia/Bangkok"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("recordedAt", EARLY_MORNING_BANGKOK);

        assertThat(mapper.convertValue(input, Holder.class).recordedAt.getOffset())
                .isEqualTo(ZoneOffset.ofHours(7));
    }

    /** {@code GenericEntityService.updateEntity} merges onto an existing instance instead. */
    @Test
    void updateValue_framesInConfiguredZone() {
        ObjectMapper mapper = JsonConverter.createObjectMapper(TimeZone.getTimeZone("Asia/Bangkok"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("recordedAt", EARLY_MORNING_BANGKOK);

        assertThat(mapper.updateValue(new Holder(), input).recordedAt.getOffset())
                .isEqualTo(ZoneOffset.ofHours(7));
    }

    @Test
    void serialize_writesConfiguredZoneOffset() {
        ObjectMapper mapper = JsonConverter.createObjectMapper(TimeZone.getTimeZone("Asia/Bangkok"));
        Holder holder = new Holder();
        holder.recordedAt = OffsetDateTime.parse(EARLY_MORNING_BANGKOK);

        assertThat(mapper.writeValueAsString(holder)).contains("2026-08-25T06:57:17+07:00");
    }

    /**
     * The JSON column round-trip goes through the static mapper, so a value stored from a
     * consumer entity comes back in the JVM frame rather than in UTC.
     */
    @Test
    void jsonColumnRoundTrip_preservesWallClockInJvmZone() {
        JsonConverter converter = new JsonConverter();
        OffsetDateTime original = OffsetDateTime.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recordedAt", original);

        Object back = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(payload));

        OffsetDateTime restored = OffsetDateTime.parse((String) ((Map<?, ?>) back).get("recordedAt"));
        assertThat(restored.toInstant()).isEqualTo(original.toInstant());
        assertThat(restored.getOffset()).isEqualTo(ZoneId.systemDefault().getRules().getOffset(original.toInstant()));
    }
}

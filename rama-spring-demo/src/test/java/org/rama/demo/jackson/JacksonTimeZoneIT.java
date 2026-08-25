package org.rama.demo.jackson;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GenericEntityService}, {@code GenericApiService}, {@code SystemLogService} and
 * {@code DefaultMeilisearchMapper} all inject Boot's managed {@link JsonMapper}, not the
 * starter's {@code ramaStarterObjectMapper}. Both must be framed in the JVM zone, or a
 * mutation input arriving as {@code +07:00} is re-framed to {@code Z} for the duration of
 * the request — which is exactly when {@code @PrePersist} listeners and validators read it.
 * See starter#39.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
class JacksonTimeZoneIT {

    public static class Holder {
        public OffsetDateTime recordedAt;
    }

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ObjectMapper ramaStarterObjectMapper;

    @Test
    void managedJsonMapper_isFramedInJvmZone() {
        assertThat(jsonMapper.deserializationConfig().getTimeZone()).isEqualTo(TimeZone.getDefault());
    }

    @Test
    void starterObjectMapper_isFramedInJvmZone() {
        assertThat(ramaStarterObjectMapper.deserializationConfig().getTimeZone()).isEqualTo(TimeZone.getDefault());
    }

    /** The exact path {@code GenericEntityService.createEntity} takes for every mutation input map. */
    @Test
    void convertValue_keepsInstantAndFramesInJvmZone() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("recordedAt", "2026-08-25T06:57:17+07:00");

        OffsetDateTime converted = jsonMapper.convertValue(input, Holder.class).recordedAt;

        Instant expected = Instant.parse("2026-08-24T23:57:17Z");
        assertThat(converted.toInstant()).isEqualTo(expected);
        assertThat(converted.getOffset()).isEqualTo(ZoneId.systemDefault().getRules().getOffset(expected));
    }
}

package org.rama.demo.jackson;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code spring.jackson.time-zone} used to be silently inert for starter consumers: the
 * starter built {@code ramaStarterObjectMapper} outside Boot's customizer chain, so the
 * property was read and then ignored, with no warning. Both mappers must now honour it.
 * See starter#39.
 */
@Tag("integration")
@SpringBootTest(properties = "spring.jackson.time-zone=Asia/Bangkok")
@ActiveProfiles("h2")
class JacksonTimeZonePropertyIT {

    private static final TimeZone BANGKOK = TimeZone.getTimeZone("Asia/Bangkok");

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ObjectMapper ramaStarterObjectMapper;

    @Test
    void managedJsonMapper_honoursConfiguredTimeZone() {
        assertThat(jsonMapper.deserializationConfig().getTimeZone()).isEqualTo(BANGKOK);
    }

    @Test
    void starterObjectMapper_honoursConfiguredTimeZone() {
        assertThat(ramaStarterObjectMapper.deserializationConfig().getTimeZone()).isEqualTo(BANGKOK);
    }

    @Test
    void convertValue_framesInConfiguredZone() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("recordedAt", "2026-08-24T23:57:17Z");

        OffsetDateTime converted = jsonMapper.convertValue(input, JacksonTimeZoneIT.Holder.class).recordedAt;

        assertThat(converted.getOffset()).isEqualTo(ZoneOffset.ofHours(7));
        assertThat(converted.toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 25));
    }
}

package org.rama.demo.jackson;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code spring.jackson.time-zone} must still win over the starter's default.
 *
 * <p>{@code ramaStarterTimeZoneCustomizer} is ordered {@code HIGHEST_PRECEDENCE} and Boot's
 * own customizer is {@code Ordered} at 0, so the starter sets the JVM zone first and Boot
 * overwrites it whenever the property is set. This is what keeps the documented property
 * meaningful rather than silently inert. See starter#39.
 */
@Tag("integration")
@SpringBootTest(properties = "spring.jackson.time-zone=America/New_York")
class JacksonTimeZonePropertyIT {

    /**
     * Deliberately a zone no host here runs in. Dev workstations use {@code TZ=Asia/Bangkok}
     * and the CI runner uses UTC, so asserting on either would pass on one of them even if
     * the property were ignored entirely. The guard below pins that.
     */
    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void configuredTimeZone_overridesTheStarterDefault() {
        assertThat(NEW_YORK).isNotEqualTo(TimeZone.getDefault());
        assertThat(jsonMapper).isSameAs(ctx.getBean("jacksonJsonMapper"));
        assertThat(jsonMapper.deserializationConfig().getTimeZone()).isEqualTo(NEW_YORK);
    }

    @Test
    void convertValue_framesInConfiguredZone() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("recordedAt", "2026-08-25T04:00:00Z");

        OffsetDateTime converted = jsonMapper.convertValue(input, JacksonTimeZoneIT.Holder.class).recordedAt;

        // 04:00Z is midnight in New York on the 25th (EDT, -04:00).
        assertThat(converted.getOffset()).isEqualTo(ZoneOffset.ofHours(-4));
        assertThat(converted.toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 25));
    }

    /** The coercion customizer must survive alongside the property-driven zone. */
    @Test
    void coercionCustomizer_stillApplies() {
        assertThat(jsonMapper.readValue("{\"value\":\"1\"}", JacksonTimeZoneIT.Coercible.class).value).isEqualTo(1);
    }
}

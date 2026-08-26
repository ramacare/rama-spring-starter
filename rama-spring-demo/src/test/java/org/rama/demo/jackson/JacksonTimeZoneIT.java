package org.rama.demo.jackson;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.rama.service.GenericApiService;
import org.rama.service.GenericEntityService;
import org.rama.service.system.SystemLogService;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GenericEntityService}, {@code GenericApiService}, {@code SystemLogService},
 * {@code MeilisearchService} and {@code DefaultMeilisearchMapper} all declare
 * {@link JsonMapper} and therefore all receive Boot's single managed {@code jacksonJsonMapper}
 * — <em>not</em> {@code ramaStarterObjectMapper}, which backs off in any context that has
 * Boot's Jackson auto-configuration.
 *
 * <p>That one bean must be framed in the JVM zone, or a mutation input arriving as
 * {@code +07:00} is re-framed to {@code Z} for the duration of the request — which is
 * exactly when {@code @PrePersist} listeners and validators read it. See starter#39.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
class JacksonTimeZoneIT {

    public static class Holder {
        public OffsetDateTime recordedAt;
    }

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private JsonMapper jsonMapper;

    private static Object field(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * Pins the wiring the fix depends on: exactly one {@link JsonMapper} bean exists, and it
     * is Boot's. If a second one ever appears, injection by type becomes ambiguous and the
     * services could silently pick up an unframed mapper.
     */
    @Test
    void exactlyOneManagedJsonMapperExists_andItIsBoots() {
        assertThat(ctx.getBeanNamesForType(JsonMapper.class)).containsExactly("jacksonJsonMapper");
        assertThat(jsonMapper).isSameAs(ctx.getBean("jacksonJsonMapper"));
    }

    /**
     * {@code ramaStarterObjectMapper} is guarded by a {@code @ConditionalOnMissingBean} typed
     * by its return type, {@link ObjectMapper}. Boot's {@code jacksonJsonMapper} is one, so
     * the starter's bean backs off and never registers here. This is invisible at an
     * injection point — {@code @Autowired ObjectMapper ramaStarterObjectMapper} resolves to
     * Boot's bean by type whatever the field is called — so it is asserted directly.
     */
    @Test
    void starterFallbackMapper_doesNotRegisterAlongsideBoots() {
        assertThat(ctx.containsBean("ramaStarterObjectMapper")).isFalse();
    }

    @Test
    void managedJsonMapper_isFramedInJvmZone() {
        JsonMapper boot = (JsonMapper) ctx.getBean("jacksonJsonMapper");

        assertThat(boot.deserializationConfig().getTimeZone()).isEqualTo(TimeZone.getDefault());
        assertThat(boot.serializationConfig().getTimeZone()).isEqualTo(TimeZone.getDefault());
    }

    /**
     * Both starter customizers land on the same builder. Boot collects every
     * {@code JsonMapperBuilderCustomizer} into one list and applies them in order, so
     * {@code ramaStarterTimeZoneCustomizer} and {@code ramaStarterCoercionCustomizer}
     * compose rather than one replacing the other.
     */
    @Test
    void bothStarterCustomizers_areAppliedToTheSameMapper() {
        ObjectMapper boot = (ObjectMapper) ctx.getBean("jacksonJsonMapper");

        // ramaStarterTimeZoneCustomizer
        assertThat(boot.deserializationConfig().getTimeZone()).isEqualTo(TimeZone.getDefault());
        // ramaStarterCoercionCustomizer — Jackson 3 would fail on "1" -> int without it
        assertThat(boot.readValue("{\"value\":\"1\"}", Coercible.class).value).isEqualTo(1);
    }

    public static class Coercible {
        public int value;
    }

    /** Every service on the mutation path must hold that same framed instance. */
    @Test
    void starterServices_holdTheManagedMapper() throws Exception {
        Object boot = ctx.getBean("jacksonJsonMapper");

        assertThat(field(AopTestUtils.getUltimateTargetObject(ctx.getBean(GenericEntityService.class)), "mapper"))
                .isSameAs(boot);
        assertThat(field(AopTestUtils.getUltimateTargetObject(ctx.getBean(GenericApiService.class)), "objectMapper"))
                .isSameAs(boot);
        assertThat(field(AopTestUtils.getUltimateTargetObject(ctx.getBean(SystemLogService.class)), "objectMapper"))
                .isSameAs(boot);
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

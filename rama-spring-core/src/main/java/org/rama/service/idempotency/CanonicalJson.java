package org.rama.service.idempotency;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.rama.annotation.VolatileForIdempotency;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.introspect.JacksonAnnotationIntrospector;
import tools.jackson.databind.json.JsonMapper;

import java.util.TimeZone;

import java.util.Set;

/**
 * Stable JSON for idempotency signature derivation.
 *
 *   - Map keys sorted lexicographically.
 *   - POJO properties sorted lexicographically.
 *   - Null fields stripped.
 *   - Well-known volatile field names ({@code requestDatetime}, {@code traceId})
 *     stripped.
 *   - Any member annotated {@link VolatileForIdempotency} stripped.
 *
 * The output is intended to feed a SHA-256 — humans aren't expected to read it.
 */
public final class CanonicalJson {

    private static final Set<String> HARDCODED_VOLATILE_NAMES = Set.of("requestDatetime", "traceId");

    private static final ObjectMapper MAPPER = build();

    private CanonicalJson() {}

    public static String stringify(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    private static ObjectMapper build() {
        return JsonMapper.builder()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .changeDefaultPropertyInclusion(inc -> inc.withValueInclusion(JsonInclude.Include.NON_NULL))
                .annotationIntrospector(new VolatileIntrospector())
                // Deliberately UTC, not the JVM zone. This mapper exists to produce a
                // stable byte-for-byte rendering for idempotency hashing; framing it in
                // the JVM zone would make the same request hash differently across
                // deployments, and would invalidate every stored signature on a zone
                // change. Unlike JsonConverter, nothing downstream reads a wall clock
                // off this output. See starter#39.
                .defaultTimeZone(TimeZone.getTimeZone("UTC"))
                .build();
    }

    static final class VolatileIntrospector extends JacksonAnnotationIntrospector {
        @Override
        public boolean hasIgnoreMarker(MapperConfig<?> config, AnnotatedMember m) {
            if (super.hasIgnoreMarker(config, m)) return true;
            if (m.hasAnnotation(VolatileForIdempotency.class)) return true;
            return HARDCODED_VOLATILE_NAMES.contains(m.getName());
        }
    }
}

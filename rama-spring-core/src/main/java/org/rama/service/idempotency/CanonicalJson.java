package org.rama.service.idempotency;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.rama.annotation.VolatileForIdempotency;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.introspect.JacksonAnnotationIntrospector;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Stable JSON for idempotency signature derivation.
 *
 *   - Map keys sorted lexicographically.
 *   - POJO properties sorted lexicographically.
 *   - Null fields stripped.
 *   - Well-known volatile names ({@code requestDatetime}, {@code traceId}), plus any
 *     extra names the caller passes, stripped at every depth — from map keys as well as
 *     POJO properties.
 *   - Any member annotated {@link VolatileForIdempotency} stripped.
 *
 * The volatile names are removed from the rendered tree rather than through the
 * annotation introspector: Jackson consults the introspector for POJO properties only,
 * so map keys — and every Rama mutation takes {@code @Argument Map<String, Object>
 * input} — reached the hash untouched and each request signed differently. See starter#67.
 *
 * The output is intended to feed a SHA-256 — humans aren't expected to read it.
 */
public final class CanonicalJson {

    static final Set<String> HARDCODED_VOLATILE_NAMES = Set.of("requestDatetime", "traceId");

    private static final ObjectMapper MAPPER = build();

    private CanonicalJson() {}

    public static String stringify(Object value) {
        return stringify(value, Set.of());
    }

    /**
     * @param extraVolatileNames property / key names to strip at every depth on top of
     *                           {@link #HARDCODED_VOLATILE_NAMES}
     */
    public static String stringify(Object value, Set<String> extraVolatileNames) {
        JsonNode tree = MAPPER.valueToTree(value);
        if (tree == null) {
            return MAPPER.writeValueAsString(null);
        }
        Set<String> names = HARDCODED_VOLATILE_NAMES;
        if (extraVolatileNames != null && !extraVolatileNames.isEmpty()) {
            names = new HashSet<>(HARDCODED_VOLATILE_NAMES);
            names.addAll(extraVolatileNames);
        }
        stripVolatile(tree, names);
        return MAPPER.writeValueAsString(tree);
    }

    private static void stripVolatile(JsonNode node, Set<String> names) {
        if (node instanceof ObjectNode object) {
            List<String> present = object.propertyNames().stream().filter(names::contains).toList();
            if (!present.isEmpty()) {
                object.remove(present);
            }
        }
        if (node.isContainer()) {
            for (JsonNode child : node.values()) {
                stripVolatile(child, names);
            }
        }
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
            return m.hasAnnotation(VolatileForIdempotency.class);
        }
    }
}

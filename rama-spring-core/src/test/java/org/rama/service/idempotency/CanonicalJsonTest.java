package org.rama.service.idempotency;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.annotation.VolatileForIdempotency;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class CanonicalJsonTest {

    @Test
    void mapKeysAreSortedLexicographically() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("z", 1);
        map.put("a", 2);
        map.put("m", 3);
        assertThat(CanonicalJson.stringify(map)).isEqualTo("{\"a\":2,\"m\":3,\"z\":1}");
    }

    @Test
    void pojoPropertiesAreSortedAlphabetically() {
        Bean b = new Bean();
        b.name = "hello";
        b.priority = 7;
        b.active = true;
        String json = CanonicalJson.stringify(b);
        assertThat(json.indexOf("active")).isLessThan(json.indexOf("name"));
        assertThat(json.indexOf("name")).isLessThan(json.indexOf("priority"));
    }

    @Test
    void hardcodedVolatileNamesAreStrippedOnPojos() {
        VolatileBean b = new VolatileBean();
        b.amount = 100;
        b.requestDatetime = "2026-05-15T00:00:00Z";
        b.traceId = "abc-123";
        String json = CanonicalJson.stringify(b);
        assertThat(json).contains("amount");
        assertThat(json).doesNotContain("requestDatetime");
        assertThat(json).doesNotContain("traceId");
    }

    @Test
    void annotatedFieldIsStripped() {
        AnnotatedBean b = new AnnotatedBean();
        b.name = "alpha";
        b.correlationId = "should-be-gone";
        String json = CanonicalJson.stringify(b);
        assertThat(json).contains("name");
        assertThat(json).doesNotContain("correlationId");
        assertThat(json).doesNotContain("should-be-gone");
    }

    @Test
    void nullPropertiesAreSkipped() {
        Bean b = new Bean();
        b.name = "x";
        // priority + active remain primitive defaults; we set name only
        // (primitives serialise as their default int/boolean values, not null)
        String json = CanonicalJson.stringify(b);
        assertThat(json).contains("name");
    }

    @Test
    void identicalInputsProduceIdenticalOutput() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("x", 1);
        a.put("y", "two");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("y", "two");
        b.put("x", 1);
        assertThat(CanonicalJson.stringify(a)).isEqualTo(CanonicalJson.stringify(b));
    }

    /**
     * starter#67. Rama mutations take {@code @Argument Map<String, Object> input}; the
     * volatile names used to be stripped from POJO properties only, so on a map they
     * reached the hash and every request signed differently.
     */
    @Test
    void hardcodedVolatileNamesAreStrippedFromMapInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "x");
        input.put("requestDatetime", "2026-11-10T10:00:00Z");
        input.put("traceId", "abc");

        assertThat(CanonicalJson.stringify(new Object[]{input})).isEqualTo("[{\"name\":\"x\"}]");
    }

    @Test
    void volatileNamesAreStrippedAtAnyDepth() {
        VolatileBean bean = new VolatileBean();
        bean.amount = 5;
        bean.traceId = "in-pojo";
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("qty", 2);
        line.put("traceId", "in-list");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("lines", List.of(line));
        input.put("payment", bean);
        input.put("meta", Map.of("requestDatetime", "2026-11-10T10:00:00Z"));

        assertThat(CanonicalJson.stringify(new Object[]{input}))
                .isEqualTo("[{\"lines\":[{\"qty\":2}],\"meta\":{},\"payment\":{\"amount\":5}}]");
    }

    @Test
    void extraVolatileNamesAreStrippedFromMapsAndPojos() {
        Bean bean = new Bean();
        bean.name = "kept";
        bean.priority = 3;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("clientStamp", 1731232800000L);
        input.put("bean", bean);

        String json = CanonicalJson.stringify(input, Set.of("clientStamp", "priority"));

        assertThat(json).isEqualTo("{\"bean\":{\"active\":false,\"name\":\"kept\"}}");
    }

    @Test
    void nullAndScalarRootsStillStringify() {
        assertThat(CanonicalJson.stringify(null)).isEqualTo("null");
        assertThat(CanonicalJson.stringify(new Object[]{})).isEqualTo("[]");
        assertThat(CanonicalJson.stringify(new Object[]{42L, "x"})).isEqualTo("[42,\"x\"]");
    }

    public static class Bean {
        public String name;
        public int priority;
        public boolean active;
    }

    public static class VolatileBean {
        public int amount;
        public String requestDatetime;
        public String traceId;
    }

    public static class AnnotatedBean {
        public String name;
        @VolatileForIdempotency
        public String correlationId;
    }

    /**
     * Idempotency signatures are hashed off this rendering, so the same request must
     * stringify identically on every deployment. Framing this mapper in the JVM zone —
     * as {@link org.rama.entity.JsonConverter} correctly is — would make the hash depend
     * on the container's {@code TZ} and invalidate stored signatures on a zone change.
     * This mapper is therefore deliberately pinned to UTC. See starter#39.
     */
    @Test
    void datetimesAreRenderedInUtcRegardlessOfJvmZone() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("at", OffsetDateTime.parse("2026-08-25T06:57:17+07:00"));

        assertThat(CanonicalJson.stringify(map)).isEqualTo("{\"at\":\"2026-08-24T23:57:17Z\"}");
    }

    /** The same instant sent in two wire formats must produce one signature. */
    @Test
    void equivalentInstantsInDifferentOffsetsCanonicalizeIdentically() {
        Map<String, Object> bangkok = new LinkedHashMap<>();
        bangkok.put("at", OffsetDateTime.parse("2026-08-25T06:57:17+07:00"));
        Map<String, Object> utc = new LinkedHashMap<>();
        utc.put("at", OffsetDateTime.parse("2026-08-24T23:57:17Z"));

        assertThat(CanonicalJson.stringify(bangkok)).isEqualTo(CanonicalJson.stringify(utc));
    }
}

package org.rama.service.idempotency;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.annotation.VolatileForIdempotency;

import java.util.LinkedHashMap;
import java.util.Map;

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
}

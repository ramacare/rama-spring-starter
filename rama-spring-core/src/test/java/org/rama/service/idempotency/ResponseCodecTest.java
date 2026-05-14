package org.rama.service.idempotency;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ResponseCodecTest {

    private final ResponseCodec codec = new ResponseCodec();

    @Test
    void nullEncodesToNull() {
        assertThat(codec.encode(null)).isNull();
    }

    @Test
    void nullJsonDecodesToNull() {
        assertThat(codec.decode(null, String.class)).isNull();
    }

    @Test
    void voidReturnTypeDecodesToNull() {
        assertThat(codec.decode("\"ignored\"", void.class)).isNull();
        assertThat(codec.decode("\"ignored\"", Void.class)).isNull();
    }

    @Test
    void primitiveRoundTrip() {
        String json = codec.encode(42);
        Object back = codec.decode(json, Integer.class);
        assertThat(back).isEqualTo(42);
    }

    @Test
    void stringRoundTrip() {
        String json = codec.encode("hello");
        Object back = codec.decode(json, String.class);
        assertThat(back).isEqualTo("hello");
    }

    @Test
    void listOfStringsRoundTrip() {
        Type t = new TypeReference<List<String>>() {}.getType();
        String json = codec.encode(List.of("a", "b", "c"));
        Object back = codec.decode(json, t);
        assertThat(back).isEqualTo(List.of("a", "b", "c"));
    }

    @Test
    void optionalOfPojoRoundTrip() {
        Type t = new TypeReference<Optional<Payload>>() {}.getType();
        Optional<Payload> original = Optional.of(new Payload("ok", 7));

        String json = codec.encode(original);
        @SuppressWarnings("unchecked")
        Optional<Payload> back = (Optional<Payload>) codec.decode(json, t);

        assertThat(back).isPresent();
        assertThat(back.get().message).isEqualTo("ok");
        assertThat(back.get().count).isEqualTo(7);
    }

    @Test
    void mapRoundTrip() {
        Type t = new TypeReference<Map<String, Integer>>() {}.getType();
        Map<String, Integer> original = Map.of("a", 1, "b", 2);

        String json = codec.encode(original);
        Object back = codec.decode(json, t);

        assertThat(back).isEqualTo(original);
    }

    public static class Payload {
        public String message;
        public int count;

        public Payload() {}

        public Payload(String message, int count) {
            this.message = message;
            this.count = count;
        }
    }
}

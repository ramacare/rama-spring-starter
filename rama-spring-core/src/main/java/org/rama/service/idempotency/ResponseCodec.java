package org.rama.service.idempotency;

import org.rama.entity.JsonConverter;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;

/**
 * Encodes / decodes the cached response payload for the dedup row.
 *
 * Uses the same Jackson 3 configuration as {@link JsonConverter} so cached
 * responses round-trip with the same semantics consumer code already gets
 * from the starter's JSON columns.
 */
public class ResponseCodec {

    private static final ObjectMapper MAPPER = JsonConverter.createObjectMapper();

    public String encode(Object value) {
        if (value == null) return null;
        return MAPPER.writeValueAsString(value);
    }

    public Object decode(String json, Type returnType) {
        if (json == null) return null;
        if (returnType == void.class || returnType == Void.class) return null;
        JavaType jt = MAPPER.constructType(returnType);
        return MAPPER.readValue(json, jt);
    }
}

package org.rama.starter.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SanitizeUtil {
    private SanitizeUtil() {
    }

    public static Object sanitize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            str = str.stripTrailing();
            return str.isEmpty() ? null : str;
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMapRaw(map);
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>(list.size());
            for (Object item : list) {
                sanitized.add(sanitize(item));
            }
            return sanitized;
        }
        return value;
    }

    public static <K> Map<K, Object> sanitizeMap(Map<K, ?> input) {
        Map<K, Object> sanitized = new LinkedHashMap<>(input.size());
        input.forEach((k, v) -> sanitized.put(k, sanitize(v)));
        return sanitized;
    }

    private static Map<?, Object> sanitizeMapRaw(Map<?, ?> input) {
        Map<Object, Object> sanitized = new LinkedHashMap<>(input.size());
        input.forEach((k, v) -> sanitized.put(k, sanitize(v)));
        return sanitized;
    }
}

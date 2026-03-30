package org.rama.mongo.service.transformer;

import org.rama.util.DateTimeUtil;

import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DateTransformer {
    private DateTransformer() {
    }

    public static Map<String, Object> transform(Map<String, Object> data) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            result.put(entry.getKey(), transformValue(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object transformValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return transform((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(transformValue(item));
            }
            return result;
        }
        if (value instanceof String input) {
            try {
                return DateTimeUtil.parseFlexibleOffsetDateTime(input);
            } catch (DateTimeParseException ignored) {
            }
            try {
                return DateTimeUtil.parseFlexibleLocalDate(input);
            } catch (DateTimeParseException ignored) {
            }
        }
        return value;
    }
}

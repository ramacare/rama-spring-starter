package org.rama.service.document.template;

import java.lang.reflect.Method;
import java.util.Map;

public interface ReplacementObjectHook {
    Object process(Object replacement, Map<String, String> attributes);

    default int getOrder() {
        return Integer.MAX_VALUE - 100;
    }

    default String extractMrn(Object replacement) {
        if (replacement == null) {
            return null;
        }
        if (replacement instanceof Map<?, ?> replacementMap && replacementMap.containsKey("mrn")) {
            Object value = replacementMap.get("mrn");
            return value == null ? null : value.toString();
        }
        if (replacement instanceof String) {
            return replacement.toString();
        }
        try {
            Method method = replacement.getClass().getMethod("getMrn");
            Object value = method.invoke(replacement);
            return value == null ? null : value.toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}

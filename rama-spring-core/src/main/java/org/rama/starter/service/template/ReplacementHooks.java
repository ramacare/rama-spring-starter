package org.rama.starter.service.template;

import java.util.Comparator;
import java.util.Map;

public class ReplacementHooks {
    private final Map<String, ? extends ReplacementObjectHook> objectHooks;
    private final Map<String, ? extends ReplacementStringHook> stringHooks;

    public ReplacementHooks(Map<String, ? extends ReplacementObjectHook> objectHooks, Map<String, ? extends ReplacementStringHook> stringHooks) {
        this.objectHooks = objectHooks;
        this.stringHooks = stringHooks;
    }

    public String process(Object replacement, Map<String, String> attributes) {
        Object value = replacement;
        for (var entry : objectHooks.entrySet().stream().sorted(Comparator.comparingInt(e -> e.getValue().getOrder())).toList()) {
            value = entry.getValue().process(value, attributes);
        }
        String stringValue = value == null ? "" : value.toString();
        for (var entry : stringHooks.entrySet().stream().sorted(Comparator.comparingInt(e -> e.getValue().getOrder())).toList()) {
            stringValue = entry.getValue().process(stringValue, attributes);
        }
        return stringValue;
    }
}

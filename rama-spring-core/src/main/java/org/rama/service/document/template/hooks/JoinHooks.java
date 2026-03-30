package org.rama.service.document.template.hooks;

import org.rama.service.document.replacement.ReplacementObjectHook;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class JoinHooks implements ReplacementObjectHook {
    @Override
    public Object process(Object replacement, Map<String, String> attributes) {
        if (!attributes.containsKey("join")) {
            return replacement;
        }
        String key = attributes.get("join");
        if (replacement instanceof List<?> list) {
            String delimiter = attributes.getOrDefault("delimiter", ",");
            return list.stream().map(item -> {
                if (item instanceof Map<?, ?> map) {
                    if (key != null && map.containsKey(key)) {
                        return String.valueOf(map.get(key));
                    }
                    if (map.containsKey("label")) {
                        return String.valueOf(map.get("label"));
                    }
                    if (map.containsKey("value")) {
                        return String.valueOf(map.get("value"));
                    }
                    return map.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(","));
                }
                return String.valueOf(item);
            }).collect(Collectors.joining(delimiter));
        }
        if (replacement instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(entry -> entry.getKey() + " : " + entry.getValue()).collect(Collectors.joining(attributes.getOrDefault("delimiter", "\n")));
        }
        return replacement;
    }
}

package org.rama.starter.service.template;

import java.util.Map;

public interface ReplacementStringHook {
    String process(String replacement, Map<String, String> attributes);

    default int getOrder() {
        return Integer.MAX_VALUE - 100;
    }
}

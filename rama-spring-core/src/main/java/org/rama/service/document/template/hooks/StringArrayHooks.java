package org.rama.service.document.template.hooks;

import org.rama.service.document.template.ReplacementStringHook;

import java.util.Map;

public class StringArrayHooks implements ReplacementStringHook {
    @Override
    public String process(String replacement, Map<String, String> attributes) {
        if (attributes.containsKey("stringArray") && replacement.startsWith("[") && replacement.endsWith("]")) {
            return replacement.substring(1, replacement.length() - 1);
        }
        return replacement;
    }
}

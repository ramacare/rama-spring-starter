package org.rama.service.document.template.hooks;

import org.rama.service.document.replacement.ReplacementStringHook;

import java.util.Map;

public class GeneralHooks implements ReplacementStringHook {
    @Override
    public String process(String replacement, Map<String, String> attributes) {
        String result = replacement;
        if (attributes.containsKey("prefix") && result != null && !result.isEmpty()) {
            result = attributes.get("prefix") + result;
        }
        if (attributes.containsKey("suffix") && result != null && !result.isEmpty()) {
            result = result + attributes.get("suffix");
        }
        if (attributes.containsKey("singleline") && result != null) {
            result = result.replaceAll("\\r?\\n", " ");
        }
        return result;
    }
}

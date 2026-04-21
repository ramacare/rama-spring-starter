package org.rama.service.document.template.hooks;

import org.rama.service.document.replacement.ReplacementStringHook;

import java.util.Map;

public class GeneralHooks implements ReplacementStringHook {

    private static final String QUOTE_TRIM_REGEX = "^[\"'\u201c\u201d\u2018\u2019]|[\"'\u201c\u201d\u2018\u2019]$";

    @Override
    public String process(String replacement, Map<String, String> attributes) {
        String result = replacement;
        if (attributes.containsKey("prefix") && result != null && !result.isEmpty()) {
            result = trimQuotes(attributes.get("prefix").trim()) + result;
        }
        if (attributes.containsKey("suffix") && result != null && !result.isEmpty()) {
            result = result + trimQuotes(attributes.get("suffix").trim());
        }
        if (attributes.containsKey("singleline") && result != null) {
            result = result.replaceAll("\\r?\\n", " ");
        }
        if (attributes.containsKey("tabnewline") && result != null) {
            result = result.replaceAll("\\r?\\n", "\t\n");
        }
        return result;
    }

    private static String trimQuotes(String value) {
        return value.replaceAll(QUOTE_TRIM_REGEX, "");
    }
}

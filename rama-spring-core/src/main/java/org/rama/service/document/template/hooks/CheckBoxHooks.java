package org.rama.service.document.template.hooks;

import org.rama.service.document.replacement.ReplacementObjectHook;
import org.springframework.core.convert.ConversionService;

import java.util.Map;

public class CheckBoxHooks implements ReplacementObjectHook {
    private final ConversionService conversionService;

    public CheckBoxHooks(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    @Override
    public Object process(Object replacement, Map<String, String> attributes) {
        if (!attributes.containsKey("checkbox")) {
            return replacement;
        }
        boolean checked = Boolean.TRUE.equals(conversionService.convert(replacement, Boolean.class));
        return checked ? "☒" : "☐";
    }
}

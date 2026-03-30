package org.rama.service.document.template.hooks;

import org.rama.service.document.template.ReplacementStringHook;
import org.rama.util.DateTimeUtil;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

public class DatetimeHooks implements ReplacementStringHook {
    @Override
    public String process(String replacement, Map<String, String> attributes) {
        try {
            if (attributes.containsKey("datetime")) {
                OffsetDateTime dateTime = DateTimeUtil.parseFlexibleOffsetDateTime(replacement);
                return dateTime.format(DateTimeFormatter.ofPattern(attributes.getOrDefault("format", "d MMMM yyyy HH:mm"), parseLocale(attributes.getOrDefault("locale", "th_TH"))));
            }
            if (attributes.containsKey("date")) {
                LocalDate date = DateTimeUtil.parseFlexibleLocalDate(replacement);
                return date.format(DateTimeFormatter.ofPattern(attributes.getOrDefault("format", "d MMMM yyyy"), parseLocale(attributes.getOrDefault("locale", "th_TH"))));
            }
            if (attributes.containsKey("time")) {
                return LocalTime.parse(replacement, DateTimeFormatter.ofPattern("HH:mm:ss")).format(DateTimeFormatter.ofPattern(attributes.getOrDefault("format", "HH:mm")));
            }
        } catch (Exception ignored) {
        }
        return replacement;
    }

    private Locale parseLocale(String localeValue) {
        String[] values = localeValue.split("_");
        return new Locale(values[0], values.length > 1 ? values[1] : "");
    }
}

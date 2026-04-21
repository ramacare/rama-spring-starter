package org.rama.service.document.template.hooks;

import lombok.extern.slf4j.Slf4j;
import org.rama.service.document.replacement.ReplacementStringHook;
import org.rama.util.DateTimeUtil;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.time.chrono.ThaiBuddhistChronology;
import java.time.chrono.ThaiBuddhistDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

@Slf4j
public class DatetimeHooks implements ReplacementStringHook {

    @Override
    public String process(String replacement, Map<String, String> attributes) {
        if (attributes.containsKey("datetime")) {
            return formatDateTime(replacement, attributes);
        }
        if (attributes.containsKey("date")) {
            return formatDate(replacement, attributes);
        }
        if (attributes.containsKey("time")) {
            return formatTime(replacement, attributes);
        }
        return replacement;
    }

    private String formatDateTime(String input, Map<String, String> attributes) {
        String format = attributes.getOrDefault("format", "d MMMM yyyy HH:mm");
        Locale locale = parseLocale(attributes.getOrDefault("locale", "th_TH"));
        try {
            OffsetDateTime dateTime = DateTimeUtil.parseFlexibleOffsetDateTime(input);
            ZonedDateTime zoned = dateTime.atZoneSameInstant(ZoneId.systemDefault());
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format, locale);
            if ("th".equals(locale.getLanguage())) {
                ChronoZonedDateTime<?> buddhist = ThaiBuddhistChronology.INSTANCE.zonedDateTime(zoned);
                return formatter.format(buddhist);
            }
            return formatter.format(zoned);
        } catch (Exception e) {
            log.error("Error formatting datetime '{}': {}", input, e.getMessage());
            return input;
        }
    }

    private String formatDate(String input, Map<String, String> attributes) {
        String format = attributes.getOrDefault("format", "d MMMM yyyy");
        Locale locale = parseLocale(attributes.getOrDefault("locale", "th_TH"));
        try {
            LocalDate date = DateTimeUtil.parseFlexibleLocalDate(input);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format, locale);
            if ("th".equals(locale.getLanguage())) {
                ThaiBuddhistDate buddhist = ThaiBuddhistDate.from(date);
                return formatter.format(buddhist);
            }
            return formatter.format(date);
        } catch (Exception e) {
            log.error("Error formatting date '{}': {}", input, e.getMessage());
            return input;
        }
    }

    private String formatTime(String input, Map<String, String> attributes) {
        String format = attributes.getOrDefault("format", "HH:mm");
        Locale locale = parseLocale(attributes.getOrDefault("locale", "th_TH"));
        try {
            LocalTime time = LocalTime.parse(input, DateTimeFormatter.ofPattern("HH:mm:ss"));
            return time.format(DateTimeFormatter.ofPattern(format, locale));
        } catch (Exception e) {
            log.error("Error formatting time '{}': {}", input, e.getMessage());
            return input;
        }
    }

    private Locale parseLocale(String localeValue) {
        String[] values = localeValue.split("_");
        return new Locale(values[0], values.length > 1 ? values[1] : "");
    }
}

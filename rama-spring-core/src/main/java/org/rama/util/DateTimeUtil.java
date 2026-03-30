package org.rama.util;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Slf4j
public final class DateTimeUtil {
    private DateTimeUtil() {
    }

    public static LocalDate parseFlexibleLocalDate(String input) {
        try {
            return LocalDate.parse(input);
        } catch (DateTimeParseException ignored) {
        }

        for (String pattern : new String[]{"dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy", "yyyy/MM/dd"}) {
            try {
                return LocalDate.parse(input, DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException ignored) {
            }
        }

        return parseFlexibleOffsetDateTime(input).toLocalDate();
    }

    public static OffsetDateTime parseFlexibleOffsetDateTime(String input) {
        try {
            return OffsetDateTime.parse(input);
        } catch (DateTimeParseException ignored) {
        }

        for (String pattern : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "dd/MM/yyyy HH:mm", "dd/MM/yyyy HH:mm:ss"}) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
                LocalDateTime localDateTime = LocalDateTime.parse(input, formatter);
                return localDateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
            } catch (DateTimeParseException ignored) {
            }
        }

        throw new DateTimeParseException("Unable to parse datetime", input, 0);
    }

    public static OffsetDateTime safeParseOffsetDateTime(String date) {
        if (date == null || date.trim().isEmpty()) {
            return null;
        }

        try {
            return parseFlexibleOffsetDateTime(date);
        } catch (Exception e) {
            log.error("Failed to parse datetime: {}", e.getMessage());
            return null;
        }
    }

    public static OffsetDateTime parseFromMapKeys(Map<String, Object> meta, String... keys) {
        for (String key : keys) {
            Object metaObject = meta.get(key);
            if (metaObject == null) {
                continue;
            }

            String metaVal = String.valueOf(metaObject).trim();
            if (metaVal.isEmpty()) {
                continue;
            }

            try {
                return parseFlexibleOffsetDateTime(metaVal);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid datetime format for " + key + ": " + metaVal, e);
            }
        }
        return null;
    }

    public static OffsetDateTime getStartOfToday() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    public static OffsetDateTime getEndOfToday() {
        return getStartOfToday().plusDays(1).minusNanos(1);
    }
}

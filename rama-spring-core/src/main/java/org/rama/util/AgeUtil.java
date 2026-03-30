package org.rama.util;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;

public final class AgeUtil {
    private static final DateTimeFormatter DOB_YYYY_MM_DD = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter PDATE_DD_MM_YYYY = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.US);

    private AgeUtil() {
    }

    public static String get(String dob) {
        return get(parseDob(dob), LocalDate.now());
    }

    public static String get(String dob, String pDate) {
        return get(parseDob(dob), parseAsOf(pDate));
    }

    public static String get(Date dob) {
        return get(toLocalDate(dob), LocalDate.now());
    }

    public static String get(Date dob, Date pDate) {
        return get(toLocalDate(dob), toLocalDate(pDate));
    }

    private static String get(LocalDate birthDate, LocalDate asOfDate) {
        if (birthDate == null || asOfDate == null) {
            return null;
        }
        if (asOfDate.isBefore(birthDate)) {
            return "0#0#0";
        }
        Period period = Period.between(birthDate, asOfDate);
        return period.getYears() + "#" + period.getMonths() + "#" + period.getDays();
    }

    private static LocalDate parseDob(String dob) {
        if (dob == null || dob.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dob.trim(), DOB_YYYY_MM_DD);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static LocalDate parseAsOf(String pDate) {
        if (pDate == null || pDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(pDate.trim(), PDATE_DD_MM_YYYY);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static LocalDate toLocalDate(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}

package org.rama.service.document.template.hooks;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatetimeHooksTest {

    private final DatetimeHooks hooks = new DatetimeHooks();

    @Test
    void datetime_withThaiLocale_usesBuddhistYear() {
        String result = hooks.process("2026-04-21T10:00:00+07:00", Map.of("datetime", "", "format", "yyyy"));
        assertThat(result).isEqualTo("2569");
    }

    @Test
    void datetime_withEnglishLocale_usesGregorianYear() {
        String result = hooks.process("2026-04-21T10:00:00+07:00", Map.of("datetime", "", "format", "yyyy", "locale", "en_US"));
        assertThat(result).isEqualTo("2026");
    }

    @Test
    void date_withThaiLocale_usesBuddhistYear() {
        String result = hooks.process("2026-04-21", Map.of("date", "", "format", "yyyy"));
        assertThat(result).isEqualTo("2569");
    }

    @Test
    void date_withEnglishLocale_usesGregorianYear() {
        String result = hooks.process("2026-04-21", Map.of("date", "", "format", "yyyy", "locale", "en_US"));
        assertThat(result).isEqualTo("2026");
    }

    @Test
    void time_formatsHoursAndMinutes() {
        String result = hooks.process("08:45:30", Map.of("time", "", "format", "HH:mm"));
        assertThat(result).isEqualTo("08:45");
    }

    @Test
    void unknownInput_returnsOriginalAndDoesNotThrow() {
        String result = hooks.process("not-a-date", Map.of("datetime", ""));
        assertThat(result).isEqualTo("not-a-date");
    }

    @Test
    void noRecognizedAttribute_returnsReplacementUnchanged() {
        String result = hooks.process("2026-04-21", Map.of());
        assertThat(result).isEqualTo("2026-04-21");
    }
}

package org.rama.service.document.template.hooks;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GeneralHooksTest {

    private final GeneralHooks hooks = new GeneralHooks();

    @Test
    void prefix_prependsTrimmedValueToReplacement() {
        String result = hooks.process("value", Map.of("prefix", "  -  "));
        assertThat(result).isEqualTo("-value");
    }

    @Test
    void prefix_stripsSurroundingQuotes_soInnerWhitespaceIsPreserved() {
        String result = hooks.process("value", Map.of("prefix", "\"- \""));
        assertThat(result).isEqualTo("- value");
    }

    @Test
    void prefix_stripsSmartQuotes() {
        String result = hooks.process("value", Map.of("prefix", "\u201c- \u201d"));
        assertThat(result).isEqualTo("- value");
    }

    @Test
    void suffix_appendsToReplacement() {
        String result = hooks.process("value", Map.of("suffix", ";"));
        assertThat(result).isEqualTo("value;");
    }

    @Test
    void prefix_skippedForEmptyReplacement() {
        String result = hooks.process("", Map.of("prefix", "- "));
        assertThat(result).isEqualTo("");
    }

    @Test
    void singleline_replacesNewlinesWithSpaces() {
        String result = hooks.process("a\nb\r\nc", Map.of("singleline", ""));
        assertThat(result).isEqualTo("a b c");
    }

    @Test
    void tabnewline_prependsTabToEachNewline() {
        String result = hooks.process("a\nb\r\nc", Map.of("tabnewline", ""));
        assertThat(result).isEqualTo("a\t\nb\t\nc");
    }
}

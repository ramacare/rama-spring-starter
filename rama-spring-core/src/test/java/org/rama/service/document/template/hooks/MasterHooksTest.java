package org.rama.service.document.template.hooks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rama.service.master.MasterItemService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MasterHooksTest {

    private MasterItemService masterItemService;
    private MasterHooks hooks;

    @BeforeEach
    void setUp() {
        masterItemService = mock(MasterItemService.class);
        hooks = new MasterHooks(masterItemService);
    }

    @Test
    void renders_viaWithTerminatedLookup_soHistoricalCodesResolve() {
        when(masterItemService.translateMasterWithTerminated("$group", "code", "TH")).thenReturn("Active Value");
        when(masterItemService.translateMasterWithTerminated("$group", "retired", "TH")).thenReturn("Retired Value");

        assertThat(hooks.process("code", Map.of("master", "", "groupKey", "$group"))).isEqualTo("Active Value");
        assertThat(hooks.process("retired", Map.of("master", "", "groupKey", "$group"))).isEqualTo("Retired Value");
    }

    @Test
    void withShowCode_prependsItemCodeHyphenTranslation() {
        when(masterItemService.translateMasterWithTerminated("$group", "code", "TH")).thenReturn("Translated");

        String result = (String) hooks.process("code", Map.of("master", "", "groupKey", "$group", "showCode", ""));
        assertThat(result).isEqualTo("code-Translated");
    }

    @Test
    void withEnglishLang_passesLangThrough() {
        when(masterItemService.translateMasterWithTerminated("$group", "code", "EN")).thenReturn("English");

        String result = (String) hooks.process("code", Map.of("master", "", "groupKey", "$group", "lang", "EN"));
        assertThat(result).isEqualTo("English");
    }

    @Test
    void list_translatesEachElement() {
        when(masterItemService.translateMasterWithTerminated(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1) + "!");

        Object result = hooks.process(List.of("a", "b"), Map.of("master", "", "groupKey", "$group"));
        assertThat(result).isEqualTo(List.of("a!", "b!"));
    }

    @Test
    void missingGroupKey_returnsReplacementUnchanged() {
        String result = (String) hooks.process("code", Map.of("master", ""));
        assertThat(result).isEqualTo("code");
    }

    @Test
    void missingMasterAttribute_returnsReplacementUnchanged() {
        String result = (String) hooks.process("code", Map.of("groupKey", "$group"));
        assertThat(result).isEqualTo("code");
    }
}

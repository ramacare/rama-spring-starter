package org.rama.meilisearch;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link MeilisearchIndexSettings#mergedOnto} -- the admin-override-friendly variant of
 * {@link MeilisearchIndexSettings#layeredOver} where synonyms merge per-word instead of one map
 * replacing the other outright. */
class MeilisearchIndexSettingsTest {

    @Test
    void mergedOnto_addsANewSynonymWithoutDroppingTheBasesDictionary() {
        MeilisearchIndexSettings base = MeilisearchIndexSettings.builder()
                .synonyms(Map.of("rt", new String[]{"right"}, "lt", new String[]{"left"}))
                .build();
        MeilisearchIndexSettings override = MeilisearchIndexSettings.builder()
                .synonyms(Map.of("dm", new String[]{"diabetes mellitus"}))
                .build();

        MeilisearchIndexSettings merged = override.mergedOnto(base);

        assertThat(merged.getSynonyms()).containsOnlyKeys("rt", "lt", "dm");
        assertThat(merged.getSynonyms().get("rt")).containsExactly("right");
        assertThat(merged.getSynonyms().get("dm")).containsExactly("diabetes mellitus");
    }

    @Test
    void mergedOnto_overridesASharedWordsMapping_ratherThanKeepingBoth() {
        MeilisearchIndexSettings base = MeilisearchIndexSettings.builder()
                .synonyms(Map.of("rt", new String[]{"right"}))
                .build();
        MeilisearchIndexSettings override = MeilisearchIndexSettings.builder()
                .synonyms(Map.of("rt", new String[]{"right", "correct"}))
                .build();

        MeilisearchIndexSettings merged = override.mergedOnto(base);

        assertThat(merged.getSynonyms()).containsOnlyKeys("rt");
        assertThat(merged.getSynonyms().get("rt")).containsExactly("right", "correct");
    }

    @Test
    void mergedOnto_keepsTheBasesSynonyms_whenOverrideDeclaresNone() {
        MeilisearchIndexSettings base = MeilisearchIndexSettings.builder()
                .synonyms(Map.of("rt", new String[]{"right"}))
                .build();
        MeilisearchIndexSettings override = MeilisearchIndexSettings.builder().build();

        MeilisearchIndexSettings merged = override.mergedOnto(base);

        assertThat(merged.getSynonyms()).containsOnlyKeys("rt");
    }

    @Test
    void mergedOnto_replacesEveryOtherField_exactlyLikeLayeredOver() {
        MeilisearchIndexSettings base = MeilisearchIndexSettings.builder()
                .filterableAttributes(new String[]{"groupKey"})
                .build();
        MeilisearchIndexSettings override = MeilisearchIndexSettings.builder()
                .filterableAttributes(new String[]{"id"})
                .build();

        MeilisearchIndexSettings merged = override.mergedOnto(base);

        assertThat(merged.getFilterableAttributes()).containsExactly("id");
    }
}

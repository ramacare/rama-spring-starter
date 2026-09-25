package org.rama.meilisearch;

import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.TypoTolerance;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Applies a {@link MeilisearchIndexSettings} to a Meilisearch {@link Index}. Shared by
 * {@link MeilisearchIndexInitializer} (settings straight off {@code @SyncToMeilisearch}, applied
 * once at startup per unsplit entity) and {@link org.rama.meilisearch.service.MeilisearchService}
 * (settings from a {@link MeilisearchIndexSettingsResolver}, applied lazily per split index) so
 * both paths behave identically.
 */
public final class MeilisearchIndexSettingsApplier {

    private MeilisearchIndexSettingsApplier() {
    }

    public static void apply(Index index, MeilisearchIndexSettings settings) throws MeilisearchException {
        if (settings.getSearchableAttributes().length > 0) {
            String[] current = index.getSearchableAttributesSettings();
            if (!Arrays.equals(current, settings.getSearchableAttributes())) {
                index.updateSearchableAttributesSettings(settings.getSearchableAttributes());
            }
        }

        if (settings.getFilterableAttributes().length > 0) {
            String[] current = index.getFilterableAttributesSettings();
            if (!Arrays.equals(current, settings.getFilterableAttributes())) {
                index.updateFilterableAttributesSettings(settings.getFilterableAttributes());
            }
        }

        if (settings.getSortableAttributes().length > 0) {
            String[] current = index.getSortableAttributesSettings();
            if (!Arrays.equals(current, settings.getSortableAttributes())) {
                index.updateSortableAttributesSettings(settings.getSortableAttributes());
            }
        }

        if (settings.getRankingRules().length > 0) {
            String[] current = index.getRankingRulesSettings();
            if (!Arrays.equals(current, settings.getRankingRules())) {
                index.updateRankingRulesSettings(settings.getRankingRules());
            }
        }

        // No idempotency check here (unlike the array-valued settings above): TypoTolerance has
        // no equals() override, and hand-comparing every field against
        // index.getTypoToleranceSettings() isn't worth it for a call that only ever runs once per
        // index (at startup for an unsplit entity, or on first sync for a split one).
        if (settings.getTypoToleranceEnabled() != null
                || settings.getTypoToleranceMinWordSizeOneTypo() != null
                || settings.getTypoToleranceMinWordSizeTwoTypos() != null) {
            TypoTolerance typoTolerance = new TypoTolerance();
            typoTolerance.setEnabled(settings.getTypoToleranceEnabled() == null || settings.getTypoToleranceEnabled());
            HashMap<String, Integer> minWordSizeForTypos = new HashMap<>();
            if (settings.getTypoToleranceMinWordSizeOneTypo() != null) {
                minWordSizeForTypos.put("oneTypo", settings.getTypoToleranceMinWordSizeOneTypo());
            }
            if (settings.getTypoToleranceMinWordSizeTwoTypos() != null) {
                minWordSizeForTypos.put("twoTypos", settings.getTypoToleranceMinWordSizeTwoTypos());
            }
            if (!minWordSizeForTypos.isEmpty()) {
                typoTolerance.setMinWordSizeForTypos(minWordSizeForTypos);
            }
            index.updateTypoToleranceSettings(typoTolerance);
        }

        if (!settings.getSynonyms().isEmpty()) {
            index.updateSynonymsSettings(settings.getSynonyms());
        }
    }
}

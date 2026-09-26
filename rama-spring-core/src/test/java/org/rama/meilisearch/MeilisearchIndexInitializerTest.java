package org.rama.meilisearch;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.model.TypoTolerance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.rama.annotation.SyncToMeilisearch;
import org.rama.entity.master.MasterItem;
import org.rama.meilisearch.service.MeilisearchService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The starter's own {@code @SyncToMeilisearch} entities must be initialized even though
 * {@code basePackages} only ever contains the <em>application's</em> package.
 *
 * <p>Regression guard for the bug where {@link MasterItem} (in {@code org.rama.entity.master})
 * was never scanned, so its index was auto-created by the first document write with EMPTY
 * settings — and every filtered master-data query then failed at runtime with
 * "Attribute `groupKey` is not filterable".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeilisearchIndexInitializerTest {

    @Mock private Client client;
    @Mock private MeilisearchService meilisearchService;
    @Mock private Index index;

    private MeilisearchIndexInitializer initializerFor(List<String> basePackages) {
        return initializerFor(basePackages, List.of());
    }

    private MeilisearchIndexInitializer initializerFor(List<String> basePackages, List<MeilisearchIndexSettingsResolver> resolvers) {
        when(meilisearchService.resolveIndexName(any())).thenAnswer(
                inv -> ((Class<?>) inv.getArgument(0)).getSimpleName().toLowerCase());
        when(meilisearchService.resolveIndexName(any(), anyString())).thenAnswer(
                inv -> ((Class<?>) inv.getArgument(0)).getSimpleName().toLowerCase() + "_" + inv.getArgument(1));
        when(meilisearchService.resolvePrimaryKey(any())).thenReturn("id");
        // The initializer now delegates settings computation to this method instead of doing its
        // own annotation+resolver layering -- mirror the real implementation here so this mock
        // still reflects it.
        when(meilisearchService.resolveSplitIndexSettings(any(), any())).thenAnswer(inv -> {
            Class<?> clazz = inv.getArgument(0);
            String splitFieldValue = inv.getArgument(1);
            SyncToMeilisearch annotation = clazz.getAnnotation(SyncToMeilisearch.class);
            org.rama.meilisearch.MeilisearchIndexSettings base = org.rama.meilisearch.MeilisearchIndexSettings.fromAnnotation(annotation);
            return resolvers.stream()
                    .filter(r -> r.entityClass() == clazz && java.util.Objects.equals(r.splitFieldValue(), splitFieldValue))
                    .findFirst()
                    .map(r -> r.settings().layeredOver(base))
                    .orElse(base);
        });

        when(client.getIndex(anyString())).thenReturn(index);
        when(index.getPrimaryKey()).thenReturn("id");
        // Index exists but carries no settings yet — the state a fresh Meilisearch volume
        // lands in once documents have been written but settings never applied.
        when(index.getFilterableAttributesSettings()).thenReturn(new String[0]);
        when(index.getSearchableAttributesSettings()).thenReturn(new String[0]);
        when(index.getSortableAttributesSettings()).thenReturn(new String[0]);
        when(index.getRankingRulesSettings()).thenReturn(new String[0]);

        return new MeilisearchIndexInitializer(client, meilisearchService, basePackages, resolvers, new EnsuredMeilisearchIndexes());
    }

    /**
     * The bug: only the application's package is supplied, and MasterItem lives in org.rama.
     *
     * <p>MasterItem is now split by groupKey, so eager initialization at startup only happens for
     * a split value a {@link MeilisearchIndexSettingsResolver} bean names (see the "eager,
     * resolver-driven split-index initialization" tests below) -- registering one here for
     * MasterItem exercises the same "found outside basePackages" scan behavior the original bug
     * guarded against, now through the split path.
     */
    @Test
    void initializesStarterOwnedEntity_whenOnlyAnApplicationPackageIsConfigured() {
        MeilisearchIndexSettingsResolver resolver = resolverFor(MasterItem.class, "$SNOMEDCT",
                org.rama.meilisearch.MeilisearchIndexSettings.builder().build());
        MeilisearchIndexInitializer initializer = initializerFor(List.of("com.example.app"), List.of(resolver));

        initializer.initializeIndexes();

        ArgumentCaptor<String[]> filterable = ArgumentCaptor.forClass(String[].class);
        verify(index, atLeastOnce()).updateFilterableAttributesSettings(filterable.capture());

        // Exactly the attributes declared on MasterItem's @SyncToMeilisearch, layered under the
        // resolver's (empty) settings.
        assertThat(filterable.getAllValues())
                .as("MasterItem's filterable attributes must be applied to its $SNOMEDCT split index "
                        + "even though it lives in org.rama and the app's base package is com.example.app")
                .anySatisfy(v -> assertThat(v).containsExactly("groupKey", "filterText", "statusCode"));
    }

    /** An app that nests under org.rama must not cause the same index to be initialized twice. */
    @Test
    void doesNotInitializeTheSameEntityTwice_whenBasePackagesOverlapTheStarter() {
        MeilisearchIndexSettingsResolver resolver = resolverFor(MasterItem.class, "$SNOMEDCT",
                org.rama.meilisearch.MeilisearchIndexSettings.builder().build());
        MeilisearchIndexInitializer initializer = initializerFor(List.of("org.rama"), List.of(resolver));

        initializer.initializeIndexes();

        // Exactly once -- if org.rama being both an explicit basePackage AND the starter's own
        // scan fallback caused MasterItem to be processed twice, this would be times(2).
        verify(index, times(1)).updateFilterableAttributesSettings(any());
    }

    /** Settings already correct → no redundant write to Meilisearch. */
    @Test
    void skipsUpdate_whenFilterableAttributesAlreadyMatch() {
        MeilisearchIndexSettingsResolver resolver = resolverFor(MasterItem.class, "$SNOMEDCT",
                org.rama.meilisearch.MeilisearchIndexSettings.builder().build());
        MeilisearchIndexInitializer initializer = initializerFor(List.of("com.example.app"), List.of(resolver));
        when(index.getFilterableAttributesSettings())
                .thenReturn(new String[]{"groupKey", "filterText", "statusCode"});

        initializer.initializeIndexes();

        verify(index, never()).updateFilterableAttributesSettings(any());
    }

    // ---- sortableAttributes / rankingRules / typoTolerance / synonyms ----
    // Exercised via SyncToMeilisearchExtraSettingsEntity, a dedicated fixture that deliberately
    // leaves searchableAttributes/filterableAttributes empty (see its own javadoc) so these tests
    // never interact with the searchable/filterable assertions above, no matter what MasterItem's
    // own annotation happens to declare.

    @Test
    void appliesSortableAttributes() {
        MeilisearchIndexInitializer initializer = initializerFor(List.of("com.example.app"));

        initializer.initializeIndexes();

        verify(index).updateSortableAttributesSettings(new String[]{"termLength"});
    }

    @Test
    void skipsUpdate_whenSortableAttributesAlreadyMatch() {
        MeilisearchIndexInitializer initializer = initializerFor(List.of("com.example.app"));
        when(index.getSortableAttributesSettings()).thenReturn(new String[]{"termLength"});

        initializer.initializeIndexes();

        verify(index, never()).updateSortableAttributesSettings(any());
    }

    @Test
    void appliesRankingRules() {
        MeilisearchIndexInitializer initializer = initializerFor(List.of("com.example.app"));

        initializer.initializeIndexes();

        verify(index).updateRankingRulesSettings(new String[]{
                "words", "typo", "proximity", "attribute", "sort", "exactness", "termLength:asc"});
    }

    // MasterItem's own annotation also declares typoTolerance/synonyms, so the shared mock Index
    // sees calls from both it and the fixture -- these isolate the fixture's specific call among
    // however many happened, rather than assuming there's exactly one.

    @Test
    void appliesTypoTolerance_withOnlyTheConfiguredMinWordSizes() {
        MeilisearchIndexInitializer initializer = initializerFor(List.of("com.example.app"));

        initializer.initializeIndexes();

        ArgumentCaptor<TypoTolerance> captor = ArgumentCaptor.forClass(TypoTolerance.class);
        verify(index, atLeastOnce()).updateTypoToleranceSettings(captor.capture());
        TypoTolerance applied = captor.getAllValues().stream()
                .filter(t -> Map.of("oneTypo", 3, "twoTypos", 6).equals(t.getMinWordSizeForTypos()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no updateTypoToleranceSettings call matched the fixture's thresholds"));
        assertThat(applied.isEnabled()).isTrue();
    }

    @Test
    void appliesSynonyms_oneWayPerDeclaredEntry() {
        MeilisearchIndexInitializer initializer = initializerFor(List.of("com.example.app"));

        initializer.initializeIndexes();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String[]>> captor = ArgumentCaptor.forClass(Map.class);
        verify(index, atLeastOnce()).updateSynonymsSettings(captor.capture());
        Map<String, String[]> applied = captor.getAllValues().stream()
                .filter(m -> m.containsKey("fixtureWord"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no updateSynonymsSettings call matched the fixture's dictionary"));
        assertThat(applied).containsOnlyKeys("fixtureWord");
        assertThat(applied.get("fixtureWord")).containsExactly("fixtureSynonym");
    }

    // ---- eager, resolver-driven split-index initialization ----
    // Exercised via SplitEagerInitEntity, never synced -- only MeilisearchIndexSettingsResolver
    // beans drive what gets initialized here, which is the entire point: a split index a resolver
    // names must be ready at startup, with no dependency on new data arriving first.

    private static MeilisearchIndexSettingsResolver resolverFor(Class<?> entityClass, String splitFieldValue, org.rama.meilisearch.MeilisearchIndexSettings settings) {
        return new MeilisearchIndexSettingsResolver() {
            @Override
            public Class<?> entityClass() {
                return entityClass;
            }

            @Override
            public String splitFieldValue() {
                return splitFieldValue;
            }

            @Override
            public org.rama.meilisearch.MeilisearchIndexSettings settings() {
                return settings;
            }
        };
    }

    @Test
    void eagerlyInitializesEverySplitIndexAResolverNames() {
        MeilisearchIndexSettingsResolver resolver = resolverFor(
                org.rama.entity.testfixture.SplitEagerInitEntity.class, "th",
                org.rama.meilisearch.MeilisearchIndexSettings.builder()
                        .synonyms(Map.of("bkk", new String[]{"bangkok"}))
                        .build());
        MeilisearchIndexInitializer initializer = initializerFor(List.of("com.example.app"), List.of(resolver));

        initializer.initializeIndexes();

        // atLeastOnce()/filter, not a single capture: MasterItem's own annotation and the earlier
        // group's SyncToMeilisearchExtraSettingsEntity fixture also call updateSynonymsSettings in
        // this same run (every @SyncToMeilisearch entity under org.rama.entity.* gets scanned
        // together), so this isolates the one call this resolver caused.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String[]>> synonymsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(index, atLeastOnce()).updateSynonymsSettings(synonymsCaptor.capture());
        assertThat(synonymsCaptor.getAllValues().stream().anyMatch(m -> m.containsKey("bkk")))
                .as("no updateSynonymsSettings call matched this resolver's dictionary")
                .isTrue();
        synonymsCaptor.getAllValues().stream().filter(m -> m.containsKey("bkk")).findFirst()
                .ifPresent(m -> assertThat(m.get("bkk")).containsExactly("bangkok"));

        // The resolver only overrode synonyms -- filterableAttributes still comes from
        // SplitEagerInitEntity's own @SyncToMeilisearch, layered underneath rather than dropped.
        verify(index).updateFilterableAttributesSettings(new String[]{"region"});
    }

    @Test
    void doesNotEagerlyInitializeASplitIndexNoResolverNames() {
        // No resolver at all for SplitEagerInitEntity -- it stays fully lazy (created/configured
        // on first sync instead). Its own distinguishing setting ({"region"}) must never appear
        // among the calls this run makes, however many other entities also get initialized.
        MeilisearchIndexInitializer initializer = initializerFor(List.of("com.example.app"), List.of());

        initializer.initializeIndexes();

        verify(index, never()).updateFilterableAttributesSettings(new String[]{"region"});
    }
}

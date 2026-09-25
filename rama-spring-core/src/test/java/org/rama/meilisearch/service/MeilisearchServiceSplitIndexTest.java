package org.rama.meilisearch.service;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.TaskInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.rama.entity.testfixture.SplitByCategoryEntity;
import org.rama.meilisearch.EnsuredMeilisearchIndexes;
import org.rama.meilisearch.MeilisearchIndexSettings;
import org.rama.meilisearch.MeilisearchIndexSettingsResolver;
import org.rama.meilisearch.mapper.DefaultMeilisearchMapper;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SyncToMeilisearch#indexNameField()-driven splitting: sync() must route each instance to its own
 * index by field value, create/configure that index only the first time a new value is seen, and
 * prefer a matching MeilisearchIndexSettingsResolver's settings over the annotation's own.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeilisearchServiceSplitIndexTest {

    @Mock private ApplicationContext context;
    @Mock private Client client;
    @Mock private MeilisearchErrorHandler errorHandler;
    @Mock private Index index;

    private MeilisearchService serviceWith(List<MeilisearchIndexSettingsResolver> resolvers) throws MeilisearchException {
        // Every split index is "not found yet" -> falls through to createIndex, mirroring a fresh
        // Meilisearch volume that has never seen this split-field value before.
        when(client.getIndex(anyString())).thenThrow(new MeilisearchException("not found"));
        when(client.createIndex(anyString(), anyString())).thenReturn(new TaskInfo());
        when(client.index(anyString())).thenReturn(index);
        // waitForTask is void; getTask returning null is handled safely (sync() only inspects a
        // task's status when non-null).
        when(client.getTask(org.mockito.ArgumentMatchers.anyInt())).thenReturn(null);
        when(index.addDocuments(org.mockito.ArgumentMatchers.anyString())).thenReturn(new TaskInfo());

        JsonMapper objectMapper = JsonMapper.builder().build();
        when(context.getBean(DefaultMeilisearchMapper.class)).thenReturn(new DefaultMeilisearchMapper(objectMapper));

        return new MeilisearchService(context, client, objectMapper, errorHandler, resolvers, new EnsuredMeilisearchIndexes());
    }

    @Test
    void resolveIndexName_appendsTheSanitizedSplitFieldValue() throws MeilisearchException {
        MeilisearchService service = serviceWith(List.of());

        String indexName = service.resolveIndexName(new SplitByCategoryEntity("1", "$SNOMEDCT", "n"));

        assertThat(indexName).startsWith("splittestentity_");
        assertThat(indexName).doesNotContain("$");
    }

    @Test
    void resolveIndexName_isUnaffectedForAPlainEntityWithNoIndexNameField() {
        // MasterItem itself isn't split (indexNameField is empty by default) -- confirms the new
        // instance-based overload doesn't change behavior for every other existing entity.
        MeilisearchService service = new MeilisearchService(context, client, JsonMapper.builder().build(), errorHandler);

        assertThat(service.resolveIndexName(new org.rama.entity.master.MasterItem("g", "c", "v")))
                .isEqualTo(service.resolveIndexName(org.rama.entity.master.MasterItem.class));
    }

    @Test
    void sync_createsAndConfiguresTheSplitIndex_onlyTheFirstTimeAValueIsSeen() throws MeilisearchException {
        MeilisearchService service = serviceWith(List.of());

        service.sync(new SplitByCategoryEntity("1", "$SNOMEDCT", "n1"));
        service.sync(new SplitByCategoryEntity("2", "$SNOMEDCT", "n2"));

        // Same split value both times -> the same index, created/configured exactly once.
        verify(client, times(1)).createIndex(anyString(), anyString());
    }

    @Test
    void sync_createsASeparateIndex_forADifferentSplitFieldValue() throws MeilisearchException {
        MeilisearchService service = serviceWith(List.of());

        service.sync(new SplitByCategoryEntity("1", "$SNOMEDCT", "n1"));
        service.sync(new SplitByCategoryEntity("2", "$ICD10", "n2"));

        verify(client, times(2)).createIndex(anyString(), anyString());
    }

    private static MeilisearchIndexSettingsResolver resolverFor(Class<?> entityClass, String splitFieldValue, MeilisearchIndexSettings settings) {
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
            public MeilisearchIndexSettings settings() {
                return settings;
            }
        };
    }

    @Test
    void sync_appliesTheMatchingResolversSettings_layeredOverTheAnnotation() throws MeilisearchException {
        MeilisearchIndexSettingsResolver resolver = resolverFor(SplitByCategoryEntity.class, "$SNOMEDCT",
                MeilisearchIndexSettings.builder().synonyms(Map.of("rt", new String[]{"right"})).build());
        MeilisearchService service = serviceWith(List.of(resolver));

        service.sync(new SplitByCategoryEntity("1", "$SNOMEDCT", "n1"));

        // Not a direct equality verify: Map.equals() compares values with .equals(), and
        // String[].equals() is identity-based, so two separately-built {"rt": ["right"]} maps are
        // never equal even with identical content (see also MeilisearchIndexInitializerTest's
        // synonym test for the same pitfall).
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, String[]>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(index).updateSynonymsSettings(captor.capture());
        assertThat(captor.getValue()).containsOnlyKeys("rt");
        assertThat(captor.getValue().get("rt")).containsExactly("right");

        // The resolver only overrode synonyms -- filterableAttributes still comes from
        // SplitByCategoryEntity's own @SyncToMeilisearch, layered underneath rather than dropped.
        verify(index).updateFilterableAttributesSettings(new String[]{"category"});
    }

    @Test
    void sync_ignoresAResolverForADifferentSplitFieldValue() throws MeilisearchException {
        MeilisearchIndexSettingsResolver resolver = resolverFor(SplitByCategoryEntity.class, "$SNOMEDCT",
                MeilisearchIndexSettings.builder().synonyms(Map.of("rt", new String[]{"right"})).build());
        MeilisearchService service = serviceWith(List.of(resolver));

        // A resolver registered for "$SNOMEDCT" must not affect a sync for "$ICD10".
        service.sync(new SplitByCategoryEntity("1", "$ICD10", "n1"));

        verify(index, never()).updateSynonymsSettings(any());
        verify(index).updateFilterableAttributesSettings(new String[]{"category"});
    }

    @Test
    void sync_fallsBackToTheAnnotation_whenNoResolverSupportsTheEntity() throws MeilisearchException {
        MeilisearchService service = serviceWith(List.of());

        service.sync(new SplitByCategoryEntity("1", "$SNOMEDCT", "n1"));

        // SplitByCategoryEntity's own @SyncToMeilisearch declares filterableAttributes -- that
        // still applies with no resolver in play -- but nothing else, so no other settings-update
        // calls happen.
        verify(index).updateFilterableAttributesSettings(new String[]{"category"});
        verify(index, never()).updateSearchableAttributesSettings(any());
        verify(index, never()).updateSynonymsSettings(any());
    }
}

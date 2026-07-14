package org.rama.meilisearch;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.rama.entity.master.MasterItem;
import org.rama.meilisearch.service.MeilisearchService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        when(meilisearchService.resolveIndexName(any())).thenAnswer(
                inv -> ((Class<?>) inv.getArgument(0)).getSimpleName().toLowerCase());
        when(meilisearchService.resolvePrimaryKey(any())).thenReturn("id");

        when(client.getIndex(anyString())).thenReturn(index);
        when(index.getPrimaryKey()).thenReturn("id");
        // Index exists but carries no settings yet — the state a fresh Meilisearch volume
        // lands in once documents have been written but settings never applied.
        when(index.getFilterableAttributesSettings()).thenReturn(new String[0]);
        when(index.getSearchableAttributesSettings()).thenReturn(new String[0]);

        return new MeilisearchIndexInitializer(client, meilisearchService, basePackages);
    }

    /** The bug: only the application's package is supplied, and MasterItem lives in org.rama. */
    @Test
    void initializesStarterOwnedEntity_whenOnlyAnApplicationPackageIsConfigured() {
        MeilisearchIndexInitializer initializer = initializerFor(List.of("com.example.app"));

        initializer.initializeIndexes();

        ArgumentCaptor<String[]> filterable = ArgumentCaptor.forClass(String[].class);
        verify(index).updateFilterableAttributesSettings(filterable.capture());

        // Exactly the attributes declared on MasterItem's @SyncToMeilisearch.
        assertThat(filterable.getValue())
                .as("MasterItem's filterable attributes must be applied even though it lives in "
                        + "org.rama and the app's base package is com.example.app")
                .containsExactly("groupKey", "filterText", "statusCode");
    }

    /** An app that nests under org.rama must not cause the same index to be initialized twice. */
    @Test
    void doesNotInitializeTheSameEntityTwice_whenBasePackagesOverlapTheStarter() {
        MeilisearchIndexInitializer initializer = initializerFor(List.of("org.rama"));

        initializer.initializeIndexes();

        verify(index, times(1)).updateFilterableAttributesSettings(any());
    }

    /** Settings already correct → no redundant write to Meilisearch. */
    @Test
    void skipsUpdate_whenFilterableAttributesAlreadyMatch() {
        MeilisearchIndexInitializer initializer = initializerFor(List.of("com.example.app"));
        when(index.getFilterableAttributesSettings())
                .thenReturn(new String[]{"groupKey", "filterText", "statusCode"});

        initializer.initializeIndexes();

        verify(index, never()).updateFilterableAttributesSettings(any());
    }
}

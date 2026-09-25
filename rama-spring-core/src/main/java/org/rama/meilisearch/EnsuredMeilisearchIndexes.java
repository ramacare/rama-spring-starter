package org.rama.meilisearch;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which split indexes (see {@link org.rama.annotation.SyncToMeilisearch#indexNameField()})
 * have already had their settings applied this JVM run -- shared between
 * {@link MeilisearchIndexInitializer} (which eagerly ensures every index a
 * {@link MeilisearchIndexSettingsResolver} bean names, at startup) and
 * {@link org.rama.meilisearch.service.MeilisearchService} (which lazily ensures any other split
 * index, the first time a not-yet-seen field value is synced), so neither redundantly re-applies
 * settings the other already did.
 */
public class EnsuredMeilisearchIndexes {
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    /** @return {@code true} if {@code indexName} was not already marked ensured (i.e. the caller
     * must actually apply its settings now); {@code false} if some earlier call already did. */
    public boolean markEnsured(String indexName) {
        return ensured.add(indexName);
    }
}

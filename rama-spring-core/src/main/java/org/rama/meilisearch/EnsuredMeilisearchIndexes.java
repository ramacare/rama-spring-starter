package org.rama.meilisearch;

import java.util.concurrent.CompletableFuture;
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
    private final ConcurrentHashMap<String, CompletableFuture<Void>> ensured = new ConcurrentHashMap<>();

    /**
     * Runs {@code initializer} exactly once for {@code indexName}, no matter how many callers
     * race this method concurrently for the same name -- every other caller BLOCKS until that one
     * run finishes (successfully or not) before returning, instead of proceeding as though the
     * index were already configured.
     *
     * <p>This matters because {@code MeilisearchService.sync()} calls this from many concurrent
     * {@code @Async} threads: without blocking, a losing thread would see the index "claimed" and
     * immediately write its document, even though the winning thread's settings application
     * (index creation + up to several sequential attribute updates) hadn't finished yet -- landing
     * writes, or a concurrent search, on a not-yet-filterable index.
     *
     * @return {@code true} if this call actually ran {@code initializer} (the first caller for
     * this name); {@code false} if it blocked on an earlier call instead.
     */
    public boolean ensureInitialized(String indexName, Runnable initializer) {
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> existing = ensured.putIfAbsent(indexName, mine);
        if (existing != null) {
            existing.join();
            return false;
        }
        try {
            initializer.run();
            mine.complete(null);
        } catch (RuntimeException | Error ex) {
            mine.completeExceptionally(ex);
            throw ex;
        }
        return true;
    }
}

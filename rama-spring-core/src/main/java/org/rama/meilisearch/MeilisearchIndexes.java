package org.rama.meilisearch;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;

/**
 * Gets or creates a Meilisearch index by name, correcting its primary key if it's already wrong.
 * Shared by {@link MeilisearchIndexInitializer} (one call per unsplit entity, at startup) and
 * {@link org.rama.meilisearch.service.MeilisearchService} (one call per not-yet-seen split-index
 * name, lazily on first sync).
 */
public final class MeilisearchIndexes {

    private MeilisearchIndexes() {
    }

    public static Index getOrCreate(Client meilisearchClient, String indexName, String primaryKey) throws MeilisearchException {
        try {
            Index index = meilisearchClient.getIndex(indexName);
            if (index.getPrimaryKey() == null || !index.getPrimaryKey().equals(primaryKey)) {
                meilisearchClient.updateIndex(indexName, primaryKey);
            }
            return index;
        } catch (MeilisearchException ex) {
            meilisearchClient.createIndex(indexName, primaryKey);
            return meilisearchClient.index(indexName);
        }
    }
}

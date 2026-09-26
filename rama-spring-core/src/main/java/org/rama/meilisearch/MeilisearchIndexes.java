package org.rama.meilisearch;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.TaskInfo;

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
            // createIndex enqueues an async task and returns immediately -- a caller that turns
            // around and reads/writes this index's settings right away (as every caller of this
            // method does) can otherwise hit Meilisearch before the index actually exists yet,
            // getting back something other than the expected shape and blowing up the SDK's own
            // Gson deserialization. Wait for the task the same way sync() already waits after
            // addDocuments, so the index is guaranteed to be there before we hand back the handle.
            TaskInfo taskInfo = meilisearchClient.createIndex(indexName, primaryKey);
            meilisearchClient.waitForTask(taskInfo.getTaskUid());
            return meilisearchClient.index(indexName);
        }
    }
}

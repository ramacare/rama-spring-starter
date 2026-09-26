package org.rama.meilisearch;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.TaskInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the race where a caller (every caller of {@code getOrCreate}) turns
 * around and immediately reads/writes a freshly created index's settings: {@code createIndex}
 * enqueues an async task and returns instantly, so without waiting for it, Meilisearch can still be
 * mid-creation when the settings call lands -- surfaced in practice as a Gson deserialization
 * exception on the very first sync/reapply of a genuinely new split value.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeilisearchIndexesTest {

    @Mock private Client client;
    @Mock private Index index;

    @Test
    void getOrCreate_waitsForTheCreateIndexTask_beforeReturning() throws MeilisearchException {
        when(client.getIndex(anyString())).thenThrow(new MeilisearchException("not found"));
        TaskInfo taskInfo = new TaskInfo(); // taskUid defaults to 0 -- no public setter on the SDK's own class
        when(client.createIndex(anyString(), anyString())).thenReturn(taskInfo);
        when(client.index(anyString())).thenReturn(index);

        Index result = MeilisearchIndexes.getOrCreate(client, "brand-new-index", "id");

        InOrder order = inOrder(client);
        order.verify(client).createIndex("brand-new-index", "id");
        order.verify(client).waitForTask(taskInfo.getTaskUid());
        order.verify(client).index("brand-new-index");
        assertThat(result).isSameAs(index);
    }

    @Test
    void getOrCreate_doesNotCreateOrWait_whenTheIndexAlreadyExistsWithTheRightPrimaryKey() throws MeilisearchException {
        when(client.getIndex("existing-index")).thenReturn(index);
        when(index.getPrimaryKey()).thenReturn("id");

        Index result = MeilisearchIndexes.getOrCreate(client, "existing-index", "id");

        verify(client, never()).createIndex(anyString(), anyString());
        verify(client, never()).waitForTask(org.mockito.ArgumentMatchers.anyInt());
        assertThat(result).isSameAs(index);
    }
}

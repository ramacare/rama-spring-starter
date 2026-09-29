package org.rama.meilisearch.service;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.APIError;
import com.meilisearch.sdk.exceptions.MeilisearchApiException;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.IndexStats;
import com.meilisearch.sdk.model.TaskInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeilisearchServiceTest {

    @Mock ApplicationContext context;
    @Mock Client meilisearchClient;
    @Mock JsonMapper objectMapper;
    @Mock MeilisearchErrorHandler errorHandler;

    private MeilisearchService service() {
        return new MeilisearchService(context, meilisearchClient, objectMapper, errorHandler);
    }

    @Test
    void getIndexStats_returnsTheStats_whenTheIndexExists() throws MeilisearchException {
        Index index = mock(Index.class);
        IndexStats stats = new IndexStats(42, false, null, 0, 0, 0, 0);
        when(meilisearchClient.getIndex("masteritem")).thenReturn(index);
        when(index.getStats()).thenReturn(stats);

        Optional<IndexStats> result = service().getIndexStats("masteritem");

        assertThat(result).isPresent();
        assertThat(result.get().getNumberOfDocuments()).isEqualTo(42);
    }

    @Test
    void getIndexStats_returnsEmpty_whenTheIndexDoesNotExistYet() throws MeilisearchException {
        when(meilisearchClient.getIndex("masteritem__SNOMEDCT_2925b0"))
                .thenThrow(new MeilisearchApiException(new APIError("Index not found", "index_not_found", "invalid_request", null)));

        Optional<IndexStats> result = service().getIndexStats("masteritem__SNOMEDCT_2925b0");

        assertThat(result).isEmpty();
    }

    @Test
    void getIndexStats_rethrows_forAnyOtherApiError() throws MeilisearchException {
        when(meilisearchClient.getIndex("masteritem"))
                .thenThrow(new MeilisearchApiException(new APIError("Server error", "internal", "internal", null)));

        assertThatThrownBy(() -> service().getIndexStats("masteritem"))
                .isInstanceOf(MeilisearchApiException.class);
    }

    @Test
    void deleteIndex_deletesAndWaitsForTheTask() throws MeilisearchException {
        TaskInfo taskInfo = new TaskInfo();
        when(meilisearchClient.deleteIndex("masteritem")).thenReturn(taskInfo);

        service().deleteIndex("masteritem");

        verify(meilisearchClient).waitForTask(taskInfo.getTaskUid());
    }
}

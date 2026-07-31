package org.rama.mongo.indexing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeferredIndexManagerTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private IndexOperations indexOperations;

    private DeferredIndexManager managerWithThreshold(int threshold) {
        when(mongoTemplate.indexOps(anyString())).thenReturn(indexOperations);
        when(indexOperations.getIndexInfo()).thenReturn(List.of());
        return new DeferredIndexManager(mongoTemplate, threshold, 600_000L);
    }

    private LinkedHashMap<String, Sort.Direction> fields(String... names) {
        LinkedHashMap<String, Sort.Direction> map = new LinkedHashMap<>();
        for (String name : names) {
            map.put(name, Sort.Direction.ASC);
        }
        return map;
    }

    private void track(DeferredIndexManager manager, int times, String... names) {
        for (int i = 0; i < times; i++) {
            manager.trackFields("encounter", fields(names));
        }
    }

    @Test
    void createsIndex_whenUsageReachesThresholdWithinOneWindow() {
        DeferredIndexManager manager = managerWithThreshold(3);

        track(manager, 3, "patientId", "visitDate");
        manager.autoFlushIndexes();

        ArgumentCaptor<Index> captor = ArgumentCaptor.forClass(Index.class);
        verify(indexOperations).createIndex(captor.capture());
        assertThat(captor.getValue().getIndexKeys().keySet())
                .containsExactly("patientId", "visitDate");
    }

    /**
     * The bug: {@code autoFlushIndexes()} cleared every counter on each run, so a field-set
     * queried steadily but below the threshold per window never accumulated and no index was
     * ever created — however long the application ran.
     */
    @Test
    void createsIndex_whenUsageAccumulatesAcrossFlushWindows() {
        DeferredIndexManager manager = managerWithThreshold(3);

        track(manager, 2, "patientId");
        manager.autoFlushIndexes();
        verify(indexOperations, never()).createIndex(any());

        track(manager, 1, "patientId");
        manager.autoFlushIndexes();

        verify(indexOperations).createIndex(any());
    }

    @Test
    void doesNotCreateIndexTwice_whenFlushRunsAgainAfterCreation() {
        DeferredIndexManager manager = managerWithThreshold(2);

        track(manager, 5, "patientId");
        manager.autoFlushIndexes();
        manager.autoFlushIndexes();

        verify(indexOperations, times(1)).createIndex(any());
    }

    @Test
    void doesNotCreateIndex_whenUsageStaysBelowThreshold() {
        DeferredIndexManager manager = managerWithThreshold(100);

        track(manager, 99, "patientId");
        manager.autoFlushIndexes();

        verify(indexOperations, never()).createIndex(any());
    }
}

package org.rama.job;

import com.querydsl.jpa.impl.JPAQuery;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobDataMap;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Component
public abstract class SmartSyncJob extends SmartJob {
    protected <T> void syncJob(JobDataMap jobDataMap, JPAQuery<T> jpaQuery, Consumer<T> syncMethod) {
        syncJob(jobDataMap, jpaQuery, syncMethod, null);
    }

    protected <T> void syncJob(JobDataMap jobDataMap, JPAQuery<T> jpaQuery, Consumer<T> syncMethod, Long runNextSeconds) {
        chunkJob(jobDataMap, buildSyncChunkJobRunner(jpaQuery, syncMethod), runNextSeconds);
    }

    private <T> ChunkJobRunner buildSyncChunkJobRunner(JPAQuery<T> baseQuery, Consumer<T> syncMethod) {
        return (jobDataMap, offset, offsetParameter, chunkSize, maximum) -> {
            try {
                JPAQuery<T> paginatedQuery = baseQuery.clone();
                List<T> resultList = paginatedQuery
                        .offset(offset)
                        .limit(chunkSize)
                        .fetch();

                long successCount = 0;
                for (T item : resultList) {
                    try {
                        syncMethod.accept(item);
                        successCount++;
                    } catch (Exception e) {
                        log.error("Error processing item at offset {}: {}", offset, e.getMessage(), e);
                        systemLogService.error(this.getClass().getSimpleName(), "Error syncing item: " + e.getMessage(),item);
                    }
                }

                log.info("Processed chunk at offset {}, chunkSize {}, success count: {}", offset, chunkSize, successCount);
                return successCount;

            } catch (Exception e) {
                log.error("Failed to execute chunk at offset {}: {}", offset, e.getMessage(), e);
                systemLogService.error(this.getClass().getSimpleName(), "Chunk job error: " + e.getMessage());
                return 0;
            }
        };
    }
}
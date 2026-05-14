package org.rama.job;

import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobDataMap;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Slf4j
@Component
@DisallowConcurrentExecution
public abstract class SmartRunningJob extends SmartJob {
    @FunctionalInterface
    public interface JobRunner {
        void run(long index, JobDataMap jobDataMap);
    }

    protected void runningJob(JobDataMap jobDataMap, Consumer<Long> runningMethod) {
        runningJob(jobDataMap, (index, map) -> runningMethod.accept(index), 3L);
    }

    protected void runningJob(JobDataMap jobDataMap, JobRunner runningMethod) {
        runningJob(jobDataMap, runningMethod, 3L);
    }

    protected void runningJob(JobDataMap jobDataMap, JobRunner runningMethod, Long runNextSeconds) {
        chunkJob(jobDataMap,buildRunningChunkJobRunner(runningMethod),runNextSeconds);
    }

    private <T> ChunkJobRunner buildRunningChunkJobRunner(JobRunner runningMethod) {
        return (jobDataMap, offset, offsetParameter, chunkSize, maximum) -> {
            long successCount = 0;

            for (long i = offset; i < offset + chunkSize; i++) {
                if (maximum > 0 && i > maximum) break;
                try {
                    runningMethod.run(i, jobDataMap);
                    successCount++;
                    if (offsetParameter != null) systemParameterService.setParameter(offsetParameter, Long.toString(i));
                } catch (Exception e) {
                    log.error("Error syncing item at offset {}: {}", offset, e.getMessage(), e);
                    systemLogService.error(this.getClass().getSimpleName(), "Error syncing item: " + e.getMessage());
                }
            }

            return successCount;
        };
    }
}

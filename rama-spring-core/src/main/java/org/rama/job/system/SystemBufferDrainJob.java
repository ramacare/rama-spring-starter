package org.rama.job.system;

import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.rama.entity.system.SystemBuffer;
import org.rama.repository.system.SystemBufferRepository;
import org.rama.service.system.SystemBufferDispatcher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generic drain for system_buffer. Per Quartz invocation:
 *   1. Group registered dispatchers by buffer_type.
 *   2. For each buffer_type:
 *      a. Page rows from system_buffer (ORDER BY id LIMIT batchSize)
 *      b. dispatcher.dispatch(batch)
 *      c. On success → DELETE drained rows
 *      d. On exception → UPDATE attempt_count / last_error / last_attempt_at
 *
 * Schedule frequently (every 30s) so the buffer stays small. During dispatcher-side
 * outages the buffer accumulates; recovery is automatic.
 */
@Slf4j
public class SystemBufferDrainJob extends QuartzJobBean {

    public static final String KEY_BATCH_SIZE = "batchSize";
    private static final int DEFAULT_BATCH_SIZE = 1000;

    private final SystemBufferRepository repository;
    private final Map<String, SystemBufferDispatcher> dispatchersByType;

    public SystemBufferDrainJob(SystemBufferRepository repository,
                                List<SystemBufferDispatcher> dispatchers) {
        this.repository = repository;
        this.dispatchersByType = dispatchers.stream()
                .collect(Collectors.toMap(SystemBufferDispatcher::bufferType, d -> d));
    }

    @Override
    @Transactional
    protected void executeInternal(JobExecutionContext context) {
        int batchSize = context.getMergedJobDataMap().containsKey(KEY_BATCH_SIZE)
                ? context.getMergedJobDataMap().getIntValue(KEY_BATCH_SIZE) : DEFAULT_BATCH_SIZE;
        drainAll(batchSize);
    }

    /**
     * Drain every registered dispatcher's pending rows. Public entry-point for
     * tests and ad-hoc invocation; production scheduling uses {@link #executeInternal}.
     */
    @Transactional
    public void drainAll(int batchSize) {
        for (Map.Entry<String, SystemBufferDispatcher> e : dispatchersByType.entrySet()) {
            drainOne(e.getKey(), e.getValue(), batchSize);
        }
    }

    void drainOne(String bufferType, SystemBufferDispatcher dispatcher, int batchSize) {
        List<SystemBuffer> batch = repository.findByBufferTypeOrderByIdAsc(
                bufferType, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) return;

        try {
            dispatcher.dispatch(batch);
            repository.deleteAllInBatch(batch);
            log.info("Drained {} system_buffer rows for type={}", batch.size(), bufferType);
        } catch (RuntimeException ex) {
            log.warn("Drain failed for type={}; will retry next run. Cause: {}",
                    bufferType, ex.getMessage());
            OffsetDateTime now = OffsetDateTime.now();
            for (SystemBuffer row : batch) {
                row.setAttemptCount(row.getAttemptCount() + 1);
                row.setLastError(truncate(ex.getMessage(), 2000));
                row.setLastAttemptAt(now);
            }
            repository.saveAll(batch);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

package org.rama.job.system;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobDataMap;
import org.rama.job.SmartJob;
import org.rama.repository.system.RequestDedupRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Periodic TTL eviction for {@code request_dedup}. Deletes rows whose
 * {@code expires_at} has passed so the table stays bounded.
 *
 * Extends {@link SmartJob} for parity with the rest of the platform's
 * jobs; the chunk/resume machinery isn't used here — the delete is a
 * single SQL statement.
 */
@Slf4j
@RequiredArgsConstructor
public class RequestDedupCleanupJob extends SmartJob {

    private final RequestDedupRepository repository;

    @Override
    @Transactional
    public void executeInternal(JobDataMap jobDataMap) {
        int deleted = repository.deleteExpired(OffsetDateTime.now());
        if (deleted > 0) {
            log.info("Evicted {} expired request_dedup rows", deleted);
        }
    }
}

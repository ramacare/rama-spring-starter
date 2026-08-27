package org.rama.job.system;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.quartz.JobDataMap;
import org.rama.job.SmartJob;
import org.rama.repository.system.SystemRequestDedupRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Periodic TTL eviction for {@code system_request_dedup}. Deletes rows whose
 * {@code expires_at} has passed so the table stays bounded.
 *
 * Extends {@link SmartJob} for parity with the rest of the platform's
 * jobs; the chunk/resume machinery isn't used here — the delete is a
 * single SQL statement.
 */
@Slf4j
@RequiredArgsConstructor
public class SystemRequestDedupCleanupJob extends SmartJob {

    /**
     * Null when the consumer's repository scanning does not reach
     * {@code org.rama.repository}. The job is registered regardless so that its
     * existence never depends on bean-definition ordering; with no repository
     * there is nothing to evict and it no-ops. See starter#46.
     */
    private final @Nullable SystemRequestDedupRepository repository;

    /**
     * The {@code @Transactional} here applies only when this bean is called through its
     * proxy — which the integration tests do, but Quartz does not: it enters via
     * {@code SmartJob.execute} and self-invokes down to here. The transaction the delete
     * actually needs comes from
     * {@link SystemRequestDedupRepository#deleteExpired(OffsetDateTime)}. Kept because it
     * still gives the proxied path a single transaction. See starter#47 and
     * {@link org.rama.job.SmartJob}.
     */
    @Override
    @Transactional
    public void executeInternal(JobDataMap jobDataMap) {
        if (repository == null) {
            log.debug("Skipping system_request_dedup cleanup: no SystemRequestDedupRepository bean");
            return;
        }
        int deleted = repository.deleteExpired(OffsetDateTime.now());
        if (deleted > 0) {
            log.info("Evicted {} expired system_request_dedup rows", deleted);
        }
    }
}

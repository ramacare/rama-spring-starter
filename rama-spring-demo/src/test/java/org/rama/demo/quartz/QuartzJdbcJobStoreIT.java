package org.rama.demo.quartz;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo sets no Quartz property of its own: everything comes from the starter. This is the
 * end-to-end half of starter#49 — it runs on whichever engine the active profile points at, so
 * it also covers the PostgreSQL driver delegate (without it, storing the idempotency cleanup job
 * fails with {@code Bad value for type long : \xaced…} and the context never refreshes).
 */
@Tag("integration")
@SpringBootTest
class QuartzJdbcJobStoreIT {

    @Autowired Scheduler scheduler;

    @Test
    void scheduler_usesThePersistentJobStoreContributedByTheStarter() throws SchedulerException {
        assertThat(scheduler.getMetaData().isJobStoreSupportsPersistence())
                .as("spring.quartz.job-store-type=jdbc comes from the starter's defaults; when it "
                        + "arrives too late for Boot's parse-phase condition, Quartz silently "
                        + "falls back to the in-memory RAMJobStore")
                .isTrue();
        assertThat(scheduler.getMetaData().isJobStoreClustered())
                .as("the clustered defaults are only coherent against a JDBC store")
                .isTrue();
    }

    @Test
    void idempotencyCleanupJob_isActuallyScheduled() throws SchedulerException {
        assertThat(scheduler.checkExists(
                org.quartz.JobKey.jobKey("system-request-dedup-cleanup", "rama-idempotency")))
                .as("starter#47 registered the JobDetail; it only evicts anything once a real "
                        + "scheduler has picked it up")
                .isTrue();
    }
}

package org.rama.demo.idempotency;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.rama.repository.system.SystemRequestDedupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression tests for starter#47, where {@code system_request_dedup} was never evicted.
 *
 * <p>Two independent defects sat behind that, and the existing
 * {@link IdempotencyCleanupJobIT} could see neither. It calls
 * {@code job.executeInternal(...)} on the injected bean — which goes through the proxy, so
 * the job's own {@code @Transactional} applies — and it never asks whether the job was
 * scheduled in the first place.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
class IdempotencyCleanupWiringIT {

    @Autowired ApplicationContext context;
    @Autowired SystemRequestDedupRepository repository;

    /**
     * The beans used to be gated on a {@code Scheduler} bean, and the gate never matched,
     * so nothing was ever scheduled.
     *
     * <p>This demo deliberately excludes {@code QuartzAutoConfiguration} (see
     * {@code application.properties}), so there is no {@code Scheduler} here at all — which
     * is precisely why the original defect went unnoticed, and precisely what makes this a
     * useful assertion: the beans must exist on the strength of the Quartz classes being on
     * the classpath, not on a scheduler having been wired.
     */
    @Test
    void cleanupJobDetailAndTrigger_areRegisteredWithoutASchedulerBean() {
        assertThat(context.getBeanNamesForType(org.quartz.Scheduler.class))
                .as("precondition: this context has no Scheduler, so a @ConditionalOnBean gate would not match")
                .isEmpty();

        JobDetail jobDetail = context.getBean("systemRequestDedupCleanupJobDetail", JobDetail.class);
        assertThat(jobDetail.getKey().getName()).isEqualTo("system-request-dedup-cleanup");
        assertThat(jobDetail.getKey().getGroup())
                .as("group the trigger lands in — production QRTZ_TRIGGERS had nothing here")
                .isEqualTo("rama-idempotency");

        Trigger trigger = context.getBean("systemRequestDedupCleanupTrigger", Trigger.class);
        assertThat(trigger.getKey().getGroup()).isEqualTo("rama-idempotency");
        assertThat(trigger.getJobKey()).isEqualTo(jobDetail.getKey());
    }

    /**
     * Quartz reaches the delete by self-invoking through {@code SmartJob.execute}, so no
     * transaction from the job ever applies. The repository method has to carry its own, or
     * Hibernate 7.2 rejects the modifying query outright.
     */
    @Test
    void deleteExpired_worksWithNoAmbientTransaction() {
        assertThatCode(() -> repository.deleteExpired(OffsetDateTime.now()))
                .as("a @Modifying @Query gets no transaction attribute from Spring Data, "
                        + "and @Modifying does not imply one")
                .doesNotThrowAnyException();
    }
}

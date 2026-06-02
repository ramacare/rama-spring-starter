package org.rama.demo.audit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.rama.demo.entity.book.Book;
import org.rama.demo.repository.book.BookRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that a {@link org.rama.job.SmartJob} stamps the entities it persists with a
 * synthetic Quartz principal (the job name), since the Quartz worker thread carries no
 * authenticated {@code SecurityContext}.
 *
 * Before the fix {@code createdBy} comes back null/fallback; after the fix it is the
 * job's key name.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
@Import(StampingProbeJob.class)
class QuartzUserstampStampingIT {

    @Autowired StampingProbeJob job;
    @Autowired BookRepository bookRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void smartJobExecute_stampsJobNameAsPrincipal() throws Exception {
        JobDetail detail = JobBuilder.newJob(StampingProbeJob.class)
                .withIdentity("bookAudit", "demo").storeDurably().build();

        JobExecutionContext ctx = mock(JobExecutionContext.class);
        Scheduler scheduler = mock(Scheduler.class);
        Trigger trigger = mock(Trigger.class);
        when(ctx.getJobDetail()).thenReturn(detail);
        when(ctx.getScheduler()).thenReturn(scheduler);
        when(ctx.getTrigger()).thenReturn(trigger);
        when(trigger.getKey()).thenReturn(new TriggerKey("bookAudit", "demo"));
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);

        job.execute(ctx);

        Book saved = transactionTemplate.execute(s -> bookRepository.findAll().stream()
                .filter(b -> StampingProbeJob.TITLE.equals(b.getTitle()))
                .findFirst().orElseThrow());
        String createdBy = saved.getUserstampField() == null ? null : saved.getUserstampField().getCreatedBy();
        assertThat(createdBy).isEqualTo("bookAudit");
    }
}

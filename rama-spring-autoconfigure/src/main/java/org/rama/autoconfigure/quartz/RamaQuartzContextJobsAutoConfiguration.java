package org.rama.autoconfigure.quartz;

import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

/**
 * Re-wires every {@link JobDetail}/{@link Trigger} bean in the context onto a
 * {@link SchedulerFactoryBean}, regardless of who built it.
 *
 * <p>Boot's own {@code QuartzAutoConfiguration#quartzScheduler} does this for the single
 * scheduler it builds. It backs off entirely, though, the moment a consumer defines its own
 * {@code SchedulerFactoryBean} bean -- which a consumer needing more than one scheduler (e.g.
 * ramaservice, which runs a separate document-processor scheduler) has no choice but to do.
 * That consumer's hand-built factory then never collects {@code JobDetail}/{@code Trigger}
 * beans the way Boot's would have, so anything the starter registers declaratively -- e.g. the
 * idempotency {@code SystemRequestDedupCleanupJob} in {@code RamaStarterAutoConfiguration} --
 * sits in the context as an inert bean and never reaches a real {@code Scheduler}: it is absent
 * from {@code scheduleJob}/{@code scheduleJobTrigger} and never fires.
 *
 * <p>A {@link SchedulerFactoryBeanCustomizer} is the fix, not a static helper method a consumer
 * has to remember to call: every {@code SchedulerFactoryBean} bean method in this codebase
 * (Boot's own default one and any hand-built replacement, ramaservice's included) already
 * applies every {@code SchedulerFactoryBeanCustomizer} in the context -- that is how, e.g.,
 * {@link RamaQuartzDriverDelegateAutoConfiguration} reaches a consumer's own scheduler today.
 * Riding the same mechanism means a consumer's custom {@code SchedulerConfig} needs no code for
 * this at all, today or after any future change here -- it inherits this behaviour purely by
 * depending on the starter, the same way it already inherits the driver-delegate fix.
 *
 * <p>Applying this on top of Boot's own default scheduler (the common case, e.g. his-service) is
 * redundant but harmless: Boot already set the identical {@code JobDetail}/{@code Trigger}
 * arrays from the same {@link ObjectProvider} sources moments earlier; this simply re-sets an
 * equivalent array. See starter#54.
 */
@AutoConfiguration(afterName = "org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration")
@ConditionalOnClass({Scheduler.class, SchedulerFactoryBean.class})
@ConditionalOnProperty(prefix = "rama.quartz", name = "apply-defaults", havingValue = "true", matchIfMissing = true)
public class RamaQuartzContextJobsAutoConfiguration {

    /**
     * Ordered after {@link RamaQuartzDriverDelegateAutoConfiguration}'s customizer
     * ({@code @Order(1)}): that one contributes Quartz properties, this one contributes
     * job/trigger arrays -- independent {@link SchedulerFactoryBean} properties, so the
     * relative order genuinely does not matter, but staying explicit keeps future customizers
     * easy to slot in without guessing.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    SchedulerFactoryBeanCustomizer ramaQuartzContextJobsCustomizer(ObjectProvider<JobDetail> jobDetails,
            ObjectProvider<Trigger> triggers) {
        return schedulerFactoryBean -> {
            schedulerFactoryBean.setJobDetails(jobDetails.orderedStream().toArray(JobDetail[]::new));
            schedulerFactoryBean.setTriggers(triggers.orderedStream().toArray(Trigger[]::new));
        };
    }
}

package org.rama.demo.quartz;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.rama.autoconfigure.quartz.RamaQuartzContextJobsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Regression coverage for starter#54: a consumer that builds its own {@link SchedulerFactoryBean}
 * (ramaservice's {@code SchedulerConfig}, needed for its separate document-processor scheduler)
 * makes Boot's {@code QuartzAutoConfiguration} back off entirely -- including its usual collection
 * of every {@code JobDetail}/{@code Trigger} bean onto the scheduler it builds. Without this
 * customizer, a starter-contributed declarative job (e.g. the idempotency cleanup job) sits in the
 * context as an inert bean and never reaches a real {@code Scheduler}.
 */
@Tag("unit")
class RamaQuartzContextJobsCustomizerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RamaQuartzContextJobsAutoConfiguration.class));

    @Test
    void wiresEveryContextJobDetailAndTriggerOntoWhicheverSchedulerFactoryBeanItIsGiven() {
        JobDetail jobDetail = JobBuilder.newJob(NoopJob.class)
                .withIdentity("demo-job", "demo-group")
                .storeDurably()
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withIdentity("demo-job-trigger", "demo-group")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().repeatForever())
                .build();

        runner.withBean("jobDetail", JobDetail.class, () -> jobDetail)
                .withBean("trigger", Trigger.class, () -> trigger)
                .run(context -> {
                    assertThat(context).hasSingleBean(SchedulerFactoryBeanCustomizer.class);

                    // Stands in for a consumer's own hand-built SchedulerFactoryBean, e.g.
                    // ramaservice's SchedulerConfig -- the exact scenario Boot's own auto-configured
                    // scheduler never has to deal with, since it collects these beans itself.
                    SchedulerFactoryBean factory = mock(SchedulerFactoryBean.class);

                    context.getBean(SchedulerFactoryBeanCustomizer.class).customize(factory);

                    verify(factory).setJobDetails(jobDetail);
                    verify(factory).setTriggers(trigger);
                });
    }

    @Test
    void whenNoJobDetailOrTriggerBeansExist_setsEmptyArraysRatherThanSkipping() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SchedulerFactoryBeanCustomizer.class);
            SchedulerFactoryBean factory = mock(SchedulerFactoryBean.class);

            context.getBean(SchedulerFactoryBeanCustomizer.class).customize(factory);

            verify(factory).setJobDetails();
            verify(factory).setTriggers();
        });
    }

    @Test
    void whenDefaultsAreDisabled_theCustomizerIsNotRegisteredAtAll() {
        runner.withPropertyValues("rama.quartz.apply-defaults=false")
                .run(context -> assertThat(context).doesNotHaveBean(SchedulerFactoryBeanCustomizer.class));
    }

    public static class NoopJob extends QuartzJobBean {
        @Override
        protected void executeInternal(org.quartz.JobExecutionContext context) {
            // no-op: exists only so JobBuilder.newJob(...) has a real class to point at
        }
    }
}

package org.rama.service.system;

import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
public class QuartzService {
    private final Scheduler scheduler;
    private final List<String> jobScanBasePackages;

    public QuartzService(Scheduler scheduler) {
        this(scheduler, List.of());
    }

    public QuartzService(Scheduler scheduler, List<String> jobScanBasePackages) {
        this.scheduler = scheduler;
        this.jobScanBasePackages = jobScanBasePackages;
    }

    public List<String> getAllJobClasses() {
        List<String> jobClasses = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Job.class));

        for (String basePackage : jobScanBasePackages) {
            for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
                try {
                    Class<?> clazz = Class.forName(bd.getBeanClassName());
                    if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers())) {
                        jobClasses.add(clazz.getName());
                    }
                } catch (ClassNotFoundException e) {
                    log.warn("Class not found during scanning: {}", bd.getBeanClassName(), e);
                }
            }
        }
        return jobClasses;
    }

    public List<Map<String, Object>> getAllSchedules() {
        return getAllSchedules(this.scheduler);
    }

    public List<Map<String, Object>> getAllSchedules(Scheduler targetScheduler) {
        List<Map<String, Object>> schedules = new ArrayList<>();
        try {
            for (String group : targetScheduler.getJobGroupNames()) {
                for (JobKey jobKey : targetScheduler.getJobKeys(GroupMatcher.jobGroupEquals(group))) {
                    List<? extends Trigger> triggers = targetScheduler.getTriggersOfJob(jobKey);
                    for (Trigger trigger : triggers) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("jobName", jobKey.getName());
                        map.put("jobGroup", jobKey.getGroup());
                        map.put("triggerName", trigger.getKey().getName());
                        map.put("triggerGroup", trigger.getKey().getGroup());
                        map.put("nextFireTime", toOffsetDateTime(trigger.getNextFireTime()));
                        map.put("previousFireTime", toOffsetDateTime(trigger.getPreviousFireTime()));
                        map.put("triggerType", trigger.getClass().getSimpleName());
                        if (trigger instanceof CronTrigger cronTrigger) {
                            map.put("cronExpression", cronTrigger.getCronExpression());
                        } else if (trigger instanceof SimpleTrigger simpleTrigger) {
                            map.put("repeatCount", simpleTrigger.getRepeatCount());
                            map.put("repeatInterval", simpleTrigger.getRepeatInterval());
                        }
                        schedules.add(map);
                    }
                }
            }
        } catch (SchedulerException e) {
            log.error("Error retrieving trigger schedules", e);
        }
        return schedules;
    }

    @SuppressWarnings("unchecked")
    public boolean scheduleJob(String name, String group, String cronExpression, String jobClass, String description, Map<?, ?> jobData) {
        return scheduleJob(name, group, cronExpression, jobClass, description, jobData, null);
    }

    @SuppressWarnings("unchecked")
    public boolean scheduleJob(String name, String group, String cronExpression, String jobClass, String description, Map<?, ?> jobData, Scheduler targetScheduler) {
        Scheduler currentScheduler = targetScheduler != null ? targetScheduler : this.scheduler;
        try {
            Class<? extends Job> clazz = resolveJobClass(jobClass);
            JobDataMap jobDataMap = jobData != null ? new JobDataMap(jobData) : new JobDataMap();
            JobDetail jobDetail = JobBuilder.newJob(clazz)
                    .storeDurably(true)
                    .usingJobData(jobDataMap)
                    .withIdentity(name, group)
                    .withDescription(description)
                    .build();

            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .forJob(jobDetail)
                    .withIdentity(name + "Trigger", group)
                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                    .build();

            currentScheduler.addJob(jobDetail, true);
            currentScheduler.scheduleJob(trigger);
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean scheduleOneTimeJob(String name, String group, String jobClass, String description, Map<?, ?> jobData) {
        return scheduleOneTimeJob(name, group, jobClass, description, jobData, null);
    }

    public boolean scheduleOneTimeJob(String name, String group, String jobClass, String description, Map<?, ?> jobData, Scheduler targetScheduler) {
        Scheduler currentScheduler = targetScheduler != null ? targetScheduler : this.scheduler;
        try {
            Class<? extends Job> clazz = resolveJobClass(jobClass);
            JobDataMap jobDataMap = jobData != null ? new JobDataMap(jobData) : new JobDataMap();
            JobDetail jobDetail = JobBuilder.newJob(clazz)
                    .storeDurably(true)
                    .usingJobData(new JobDataMap(jobDataMap))
                    .withIdentity(name, group)
                    .withDescription(description)
                    .build();

            SimpleTrigger trigger = TriggerBuilder.newTrigger()
                    .forJob(jobDetail)
                    .withIdentity(name + "Trigger", group)
                    .startAt(Date.from(Instant.now().plusSeconds(3)))
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule().withRepeatCount(0).withIntervalInSeconds(0))
                    .build();

            currentScheduler.addJob(jobDetail, true);
            currentScheduler.scheduleJob(trigger);
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean pauseJob(String name, String group) {
        try {
            scheduler.pauseJob(JobKey.jobKey(name, group));
            return true;
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean resumeJob(String name, String group) {
        try {
            scheduler.resumeJob(JobKey.jobKey(name, group));
            return true;
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean deleteJob(String name, String group) {
        return deleteJob(name, group, null);
    }

    public boolean deleteJob(String name, String group, Scheduler targetScheduler) {
        Scheduler currentScheduler = targetScheduler != null ? targetScheduler : this.scheduler;
        try {
            return currentScheduler.deleteJob(JobKey.jobKey(name, group));
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean triggerNow(String name, String group) {
        try {
            scheduler.triggerJob(JobKey.jobKey(name, group));
            return true;
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }

    public List<JobDetail> getAllJobs() throws SchedulerException {
        return getAllJobs(this.scheduler);
    }

    public List<JobDetail> getAllJobs(Scheduler targetScheduler) throws SchedulerException {
        List<JobDetail> jobs = new ArrayList<>();
        for (String group : targetScheduler.getJobGroupNames()) {
            for (JobKey jobKey : targetScheduler.getJobKeys(GroupMatcher.jobGroupEquals(group))) {
                jobs.add(targetScheduler.getJobDetail(jobKey));
            }
        }
        return jobs;
    }

    public JobDetail getJob(String name, String group) throws SchedulerException {
        return scheduler.getJobDetail(JobKey.jobKey(name, group));
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Job> resolveJobClass(String jobClass) throws ClassNotFoundException {
        Class<?> clazz = Class.forName(jobClass);
        if (!Job.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException("Class " + jobClass + " does not implement org.quartz.Job");
        }
        boolean allowed = jobScanBasePackages.stream().anyMatch(jobClass::startsWith)
                || jobClass.startsWith("org.rama.");
        if (!allowed) {
            throw new SecurityException("Job class " + jobClass + " is not in an allowed package. Allowed: " + jobScanBasePackages);
        }
        return (Class<? extends Job>) clazz;
    }

    private OffsetDateTime toOffsetDateTime(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}

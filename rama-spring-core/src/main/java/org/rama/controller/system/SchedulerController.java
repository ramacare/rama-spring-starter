package org.rama.controller.system;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.rama.entity.Response;
import org.rama.service.system.QuartzService;
import org.springframework.core.env.Environment;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class SchedulerController {
    private final QuartzService quartzService;
    private final Environment environment;
    private final List<Scheduler> schedulers;

    @QueryMapping
    public Response scheduleJobClasses() {
        return Response.of(true,null,quartzService.getAllJobClasses());
    }

    @QueryMapping
    public List<Map<String, Object>> scheduleJobTrigger() throws SchedulerException {
        List<Map<String, Object>> allSchedules = new ArrayList<>();
        for (Scheduler scheduler : schedulers) {
            String schedulerName = scheduler.getSchedulerName();
            List<Map<String, Object>> schedules = quartzService.getAllSchedules(scheduler);
            schedules.forEach(s -> s.put("scheduler", schedulerName));
            allSchedules.addAll(schedules);
        }
        return allSchedules;
    }

    @QueryMapping
    public List<Map<String, Object>> scheduleJob() throws SchedulerException {
        List<Map<String, Object>> allJobs = new ArrayList<>();
        for (Scheduler scheduler : schedulers) {
            String schedulerName = scheduler.getSchedulerName();
            List<Map<String, Object>> jobs = quartzService.getAllJobs(scheduler).stream().map(job -> {
                Map<String, Object> map = jobDetailToMap(job);
                map.put("scheduler", schedulerName);
                return map;
            }).toList();
            allJobs.addAll(jobs);
        }
        return allJobs;
    }

    @QueryMapping
    public Map<String, Object> scheduleJobByNameAndGroup(@Argument String name, @Argument String group) throws SchedulerException {
        JobDetail job = quartzService.getJob(name, group);
        if (job == null) return null;

        return jobDetailToMap(job);
    }

    @NotNull
    private Map<String, Object> jobDetailToMap(JobDetail job) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", job.getKey().getName());
        map.put("group", job.getKey().getGroup());
        map.put("description", job.getDescription());
        map.put("dataMap", job.getJobDataMap());

        return map;
    }

    @MutationMapping
    public Response createScheduleJobCron(@Argument String name, @Argument String group, @Argument String cronExpression, @Argument String jobClass, @Argument String description, @Argument Map<String, Object> jobData) {
        try {
            validatePositiveLongIfPresent(jobData, "chunkSize");
            return Response.of(quartzService.scheduleJob(name, group, cronExpression, jobClass, description,jobData));
        } catch (RuntimeException e) {
            return Response.of(false,e.getMessage(),null);
        }
    }

    @MutationMapping
    public Response createScheduleJobOneTime(@Argument String name, @Argument String group, @Argument String jobClass, @Argument String description,@Argument Map<String, Object> jobData) {
        try {
            validatePositiveLongIfPresent(jobData, "chunkSize");
            return Response.of(quartzService.scheduleOneTimeJob(name, group, jobClass, description,jobData));
        } catch (RuntimeException e) {
            return Response.of(false,e.getMessage(),null);
        }
    }

    @MutationMapping
    public Response createScheduleJobWithScheduler(@Argument String name,
                                   @Argument String group,
                                   @Argument String cronExpression,
                                   @Argument String jobClass,
                                   @Argument String description,
                                   @Argument Map<String, Object> jobData,
                                   @Argument String scheduler) {
        try {
            Map<String, Object> finalJobData = (jobData != null) ? new HashMap<>(jobData) : new HashMap<>();

            String resolvedSchedulerName = scheduler;
            if (resolvedSchedulerName == null || resolvedSchedulerName.isEmpty()) {
                resolvedSchedulerName = environment.getProperty("spring.quartz.properties.org.quartz.scheduler.instanceName");
                if (resolvedSchedulerName == null || resolvedSchedulerName.isEmpty()) {
                    String[] profiles = environment.getActiveProfiles();
                    resolvedSchedulerName = (profiles.length > 0) ? profiles[0] : "default";
                }
            }
            finalJobData.put("scheduler", resolvedSchedulerName);

            Scheduler targetScheduler = null;
            if (scheduler != null && !scheduler.isEmpty()) {
                targetScheduler = schedulers.stream()
                        .filter(s -> {
                            try { return s.getSchedulerName().equals(scheduler); }
                            catch (SchedulerException e) { return false; }
                        })
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Scheduler not found with name: " + scheduler));
            }

            validatePositiveLongIfPresent(finalJobData, "chunkSize");

            if (cronExpression != null && !cronExpression.isEmpty()) {
                return Response.of(quartzService.scheduleJob(name, group, cronExpression, jobClass, description, finalJobData, targetScheduler));
            } else {
                return Response.of(quartzService.scheduleOneTimeJob(name, group, jobClass, description, finalJobData, targetScheduler));
            }
        } catch (RuntimeException e) {
            return Response.of(false, e.getMessage(), null);
        }
    }

    @MutationMapping
    public Response pauseScheduleJob(@Argument String name, @Argument String group) {
        try {
            boolean wasPaused = false;
            for (Scheduler scheduler : schedulers) {
                JobKey key = JobKey.jobKey(name, group);
                if (scheduler.checkExists(key)) {
                    scheduler.pauseJob(key);
                    wasPaused = true;
                }
            }
            return Response.of(wasPaused, wasPaused ? "Job paused successfully." : "Job not found in any scheduler.", null);
        } catch (SchedulerException | RuntimeException e) {
            return Response.of(false, e.getMessage(), null);
        }
    }

    @MutationMapping
    public Response resumeScheduleJob(@Argument String name, @Argument String group) {
        try {
            boolean wasResumed = false;
            for (Scheduler scheduler : schedulers) {
                JobKey key = JobKey.jobKey(name, group);
                if (scheduler.checkExists(key)) {
                    scheduler.resumeJob(key);
                    wasResumed = true;
                }
            }
            return Response.of(wasResumed, wasResumed ? "Job resumed successfully." : "Job not found in any scheduler.", null);
        } catch (SchedulerException | RuntimeException e) {
            return Response.of(false, e.getMessage(), null);
        }
    }

    @MutationMapping
    public Response deleteScheduleJob(@Argument String name, @Argument String group) {
        try {
            boolean wasDeleted = false;
            for (Scheduler scheduler : schedulers) {
                if (quartzService.deleteJob(name, group, scheduler)) {
                    wasDeleted = true;
                }
            }
            return Response.of(wasDeleted, wasDeleted ? "Job deleted successfully." : "Job not found in any scheduler.", null);
        } catch (RuntimeException e) {
            return Response.of(false,e.getMessage(),null);
        }
    }

    @MutationMapping
    public Response triggerScheduleJob(@Argument String name, @Argument String group) {
        try {
            return Response.of(quartzService.triggerNow(name, group));
        } catch (RuntimeException e) {
            return Response.of(false,e.getMessage(),null);
        }
    }

    private void validatePositiveLongIfPresent(Map<String, Object> map, String key) {
        Object v = (map == null) ? null : map.get(key);
        if (v == null) {
            return;
        }
        try {
            long val = (v instanceof Number) ? ((Number) v).longValue() : Long.parseLong(v.toString());
            if (val <= 0) throw new IllegalArgumentException("Invalid " + key + " (<=0): " + val);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + key + " (not a number): " + v);
        }
    }
}

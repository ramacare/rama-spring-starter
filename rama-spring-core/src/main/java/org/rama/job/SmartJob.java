package org.rama.job;

import org.quartz.*;
import org.rama.service.system.SystemLogService;
import org.rama.service.system.SystemParameterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;

@Component
public abstract class SmartJob implements Job {
    @Autowired
    protected SystemLogService systemLogService;
    @Autowired
    protected SystemParameterService systemParameterService;

    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        JobDetail jobDetail = executeInternal(jobExecutionContext);
        JobDataMap dataMap = jobDetail.getJobDataMap();

        if (dataMap.getBoolean("runNext")) {
            Long runNextSeconds = dataMap.containsKey("runNextSeconds") ? Long.parseLong(dataMap.get("runNextSeconds").toString()) : null;
            dataMap.remove("runNext");
            dataMap.remove("runNextSeconds");
            reschedule(jobExecutionContext, jobDetail, dataMap, runNextSeconds);
        } else {
            try {
                jobExecutionContext.getScheduler().addJob(jobDetail, true);
                removeNextRun(jobExecutionContext);
            } catch (SchedulerException e) {
                throw new RuntimeException("Failed to persist job result", e);
            }
        }
    }

    public JobDetail executeInternal(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        JobDetail jobDetail = jobExecutionContext.getJobDetail();
        JobDataMap jobDataMap = jobDetail.getJobDataMap();

        executeInternal(jobDataMap);

        jobDataMap.put("lastExecutedDatetime", OffsetDateTime.now().toString());

        return jobDetail;
    }

    public abstract void executeInternal(JobDataMap jobDataMap);

    protected void reschedule(JobExecutionContext jobExecutionContext, JobDetail jobDetail, JobDataMap dataMap, Long runNextSeconds) {
        try {
            Scheduler scheduler = jobExecutionContext.getScheduler();
            JobKey jobKey = jobDetail.getKey();
            JobDetail updatedJob = JobBuilder.newJob(jobDetail.getJobClass())
                    .withIdentity(jobKey)
                    .usingJobData(new JobDataMap(dataMap))
                    .storeDurably()
                    .build();

            String nextRunTriggerKey = (jobExecutionContext.getTrigger().getKey().getName().endsWith("_nextRun")) ? jobExecutionContext.getTrigger().getKey().getName() : jobExecutionContext.getTrigger().getKey().getName() + "_nextRun";
            TriggerKey triggerKey = TriggerKey.triggerKey(nextRunTriggerKey, jobKey.getGroup());
            scheduler.unscheduleJob(triggerKey);

            TriggerBuilder<Trigger> triggerBuilder = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(updatedJob);

            Trigger trigger = (runNextSeconds == null)
                    ? triggerBuilder.startNow().build()
                    : triggerBuilder.startAt(Date.from(Instant.now().plusSeconds((runNextSeconds>3L) ? runNextSeconds : 3L))).build();

            scheduler.addJob(updatedJob, true);
            scheduler.scheduleJob(trigger);
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to reschedule job with updated state", e);
        }
    }

    protected void removeNextRun(JobExecutionContext jobExecutionContext) {
        try {
            Scheduler scheduler = jobExecutionContext.getScheduler();
            JobKey jobKey = jobExecutionContext.getJobDetail().getKey();
            TriggerKey nextRunTriggerKey = TriggerKey.triggerKey((jobExecutionContext.getTrigger().getKey().getName().endsWith("_nextRun")) ? jobExecutionContext.getTrigger().getKey().getName() : jobExecutionContext.getTrigger().getKey().getName() + "_nextRun", jobKey.getGroup());

            if (scheduler.checkExists(nextRunTriggerKey)) scheduler.unscheduleJob(nextRunTriggerKey);
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to remove next run job and trigger", e);
        }
    }

    @FunctionalInterface
    public interface ChunkJobRunner {
        long run(JobDataMap jobDataMap,long offset, String offsetParameter,long chunkSize,long maximum);
    }

    protected <T> void chunkJob(JobDataMap jobDataMap, ChunkJobRunner chunkJobRunner, Long runNextSeconds) {
        long offset = jobDataMap.containsKey("offset") ? getLongIntSafe(jobDataMap, "offset") : 0;
        long maximum = jobDataMap.containsKey("maximum") ? getLongIntSafe(jobDataMap, "maximum") : 0;

        String offsetParameter = (jobDataMap.containsKey("offsetParameter")) ? jobDataMap.getString("offsetParameter") : null;

        long chunkSize = (jobDataMap.containsKey("chunkSize")) ? getLongIntSafe(jobDataMap, "chunkSize") : 100L;

        if (offsetParameter != null) {
            String offsetParameterValue = systemParameterService.getParameter(offsetParameter);
            offsetParameterValue = offsetParameterValue == null ? "0" : offsetParameterValue;
            offset = Long.parseLong(offsetParameterValue);
        }

        if (maximum > 0) {
            chunkSize = Math.max(0, Math.min(chunkSize, maximum - offset));
        }

        long successCount = chunkJobRunner.run(jobDataMap,offset,offsetParameter,chunkSize,maximum);

        if (successCount > 0 && (maximum == 0 || offset + chunkSize < maximum)) {
            jobDataMap.put("offset", offset + chunkSize);
            jobDataMap.put("runNext", true);
            if (runNextSeconds != null && runNextSeconds > 0) {
                jobDataMap.put("runNextSeconds", runNextSeconds);
            }
            jobDataMap.put("status", "running");
        } else {
            if (maximum > 0 && offset + chunkSize > maximum) {
                jobDataMap.put("offset", maximum);
            }
            jobDataMap.put("status", "completed");
        }
    }

    protected long getLongIntSafe(JobDataMap jobDataMap, String key) {
        try {
            return jobDataMap.getLong(key);
        } catch (ClassCastException e) {
            return Long.parseLong(jobDataMap.get(key).toString());
        }
    }
}

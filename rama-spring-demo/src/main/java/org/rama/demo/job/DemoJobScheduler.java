package org.rama.demo.job;

import lombok.extern.slf4j.Slf4j;
import org.rama.service.system.QuartzService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Slf4j
@Component
public class DemoJobScheduler implements ApplicationRunner {

    private final QuartzService quartzService;

    @Value("${demo.jobs.book-audit.enabled:false}")
    private boolean bookAuditEnabled;

    @Value("${demo.jobs.book-audit.cron:0 0/5 * * * ?}")
    private String bookAuditCron;

    public DemoJobScheduler(@Nullable QuartzService quartzService) {
        this.quartzService = quartzService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (quartzService == null) {
            log.info("QuartzService not available — Quartz auto-config is excluded in this profile. Skipping job scheduling.");
            return;
        }
        if (!bookAuditEnabled) {
            log.info("BookAuditJob scheduling skipped (demo.jobs.book-audit.enabled=false)");
            return;
        }
        boolean scheduled = quartzService.scheduleJob("bookAuditJob", "demo", bookAuditCron,
                BookAuditJob.class.getName(), "Logs every Book", Collections.emptyMap());
        log.info("BookAuditJob scheduled={} cron={}", scheduled, bookAuditCron);
    }
}

package com.amanahconnect.reminder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 09:00 India time every day, after the overdue sweep has run. ShedLock keeps it to one instance; the service is idempotent anyway. */
@Component
public class ReminderJobs {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ReminderService service;
    private final Clock clock;

    public ReminderJobs(ReminderService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = "invoiceReminderJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void remind() {
        service.run(LocalDate.now(clock.withZone(IST)));
    }
}

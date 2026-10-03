package com.amanahconnect.billing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The schedule: the overdue sweep just after midnight India time, then automatic billing at 06:00 so bills arrive in the
 * morning. ShedLock lets only one API instance run each. The beans always exist (tests call them); the cron only fires when
 * {@code app.scheduling.enabled} is on.
 */
@Component
public class BillingJobs {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final BillingJobService service;
    private final Clock clock;

    public BillingJobs(BillingJobService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = "invoiceOverdueJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void markOverdue() {
        service.markOverdue(LocalDate.now(clock.withZone(IST)));
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = "invoiceGenerationJob", lockAtMostFor = "PT60M", lockAtLeastFor = "PT1M")
    public void generateRecurring() {
        service.generateDue(LocalDate.now(clock.withZone(IST)));
    }
}

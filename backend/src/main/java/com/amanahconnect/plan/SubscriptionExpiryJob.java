package com.amanahconnect.plan;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link SubscriptionExpiryService} every day at 09:00 India time. ShedLock makes sure only one API
 * instance runs it at a time. The bean always exists (so it can be called in tests); the cron only fires when
 * scheduling is enabled ({@code app.scheduling.enabled}, off in the test profile).
 */
@Component
public class SubscriptionExpiryJob {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final SubscriptionExpiryService service;
    private final SubscriptionProperties properties;
    private final Clock clock;

    public SubscriptionExpiryJob(SubscriptionExpiryService service, SubscriptionProperties properties, Clock clock) {
        this.service = service;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = "subscriptionExpiryJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        service.execute(LocalDate.now(clock.withZone(IST)), SubscriptionExpiryService.Policy.from(properties));
    }
}

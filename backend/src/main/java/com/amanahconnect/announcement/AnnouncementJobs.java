package com.amanahconnect.announcement;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: send the scheduled announcements that are due. ShedLock lets one API instance do it at a time. */
@Component
public class AnnouncementJobs {

    private final AnnouncementDispatchService service;
    private final Clock clock;

    public AnnouncementJobs(AnnouncementDispatchService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = "announcementDispatchJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT20S")
    public void dispatchDue() {
        service.dispatchDue(clock.instant());
    }
}

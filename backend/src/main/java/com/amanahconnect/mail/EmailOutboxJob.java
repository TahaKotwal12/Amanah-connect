package com.amanahconnect.mail;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 30 seconds: send what is waiting in the email outbox. ShedLock keeps it to one instance at a time; the sender is safe even without it. */
@Component
public class EmailOutboxJob {

    private final EmailSender sender;

    public EmailOutboxJob(EmailSender sender) {
        this.sender = sender;
    }

    @Scheduled(cron = "*/30 * * * * *")
    @SchedulerLock(name = "emailOutboxJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void run() {
        sender.runOnce();
    }
}

package com.amanahconnect.dataexport;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 30 seconds: build the exports that are waiting and delete the ones whose link has expired. ShedLock keeps it to one instance; the claim makes it safe regardless. */
@Component
public class DataExportJobs {

    private final DataExportService service;

    public DataExportJobs(DataExportService service) {
        this.service = service;
    }

    @Scheduled(cron = "15,45 * * * * *")
    @SchedulerLock(name = "dataExportJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5S")
    public void run() {
        service.work();
    }
}

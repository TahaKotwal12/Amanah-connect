package com.amanahconnect.announcement;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.community.CommunityStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the scheduled announcements whose time has come, community and platform ones alike. Each runs in its own transaction with
 * its row locked and its state re-checked, so it is safe to run twice or on two instances at once: an announcement goes out once.
 * A failing one is logged and left for the next run; it does not hold up the others. Announcements of a community that is not
 * ACTIVE (suspended or archived) stay scheduled and are not sent.
 */
@Service
public class AnnouncementDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementDispatchService.class);

    public record Report(int sent, int skipped, int failed) {}

    private final AnnouncementRepository announcements;
    private final AnnouncementDispatcher dispatcher;
    private final CommunityRepository communities;
    private final AuditService audit;
    private final TransactionTemplate transaction;

    public AnnouncementDispatchService(AnnouncementRepository announcements, AnnouncementDispatcher dispatcher, CommunityRepository communities, AuditService audit, PlatformTransactionManager tm) {
        this.announcements = announcements;
        this.dispatcher = dispatcher;
        this.communities = communities;
        this.audit = audit;
        this.transaction = new TransactionTemplate(tm);
    }

    public Report dispatchDue(Instant now) {
        List<UUID> due = announcements.findDueIds(now);
        int sent = 0, skipped = 0, failed = 0;
        for (UUID id : due) {
            try {
                Boolean done = transaction.execute(status -> dispatchOne(id, now));
                if (Boolean.TRUE.equals(done)) sent++;
                else skipped++;
            } catch (RuntimeException e) {
                failed++;
                log.error("Scheduled announcement {} failed: {}", id, e.toString());
            }
        }
        Report report = new Report(sent, skipped, failed);
        if (!due.isEmpty()) log.info("Announcement dispatch at {}: {}", now, report);
        return report;
    }

    private boolean dispatchOne(UUID id, Instant now) {
        Announcement a = announcements.findWithLockForDispatch(id).orElse(null);
        if (a == null || a.getStatus() != AnnouncementStatus.SCHEDULED || a.getScheduledAt() == null || a.getScheduledAt().isAfter(now)) {
            return false; // sent, unscheduled or moved by someone else since we looked
        }
        Map<String, Object> after = new LinkedHashMap<>();
        if (a.getCommunityId() == null) {
            var delivery = dispatcher.dispatchToCommunityAdmins(a);
            announcements.save(a);
            after.put("delivery", delivery);
            audit.record("PLATFORM_ANNOUNCEMENT_SENT", null, null, "Announcement", id, Map.of("status", "SCHEDULED"), withScheduled(after));
        } else {
            var community = communities.findById(a.getCommunityId()).orElse(null);
            if (community == null || community.getStatus() != CommunityStatus.ACTIVE) return false;
            var delivery = dispatcher.dispatchToMembers(a);
            announcements.save(a);
            after.put("delivery", delivery);
            audit.record("ANNOUNCEMENT_SENT", null, a.getCommunityId(), "Announcement", id, Map.of("status", "SCHEDULED"), withScheduled(after));
        }
        return true;
    }

    private static Map<String, Object> withScheduled(Map<String, Object> after) {
        after.put("status", "SENT");
        after.put("by", "schedule");
        return after;
    }
}

package com.amanahconnect.plan;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.community.CommunityStatus;
import com.amanahconnect.notification.PlatformEmails;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily subscription sweep: remind owners 7 and 1 days before expiry, mark lapsed subscriptions
 * EXPIRED, and (only when switched on) suspend communities that stayed unpaid past the grace period.
 *
 * <p>Safe to run repeatedly: reminders and the expiry notice are each sent once (flags on the row), and
 * a subscription already renewed by a later one is never reminded about. Each item runs in its own
 * transaction, so one failure cannot block the rest.
 *
 * <p>Auto-suspension is OFF by default. With it off the job only logs a warning for communities that are
 * past the grace period; it never suspends anyone unless {@code app.subscriptions.auto-suspend-enabled}
 * is set.
 */
@Service
public class SubscriptionExpiryService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryService.class);

    private static final String NOT_RENEWED =
            " AND NOT EXISTS (SELECT 1 FROM platform_subscriptions n WHERE n.community_id = s.community_id"
                    + " AND n.status = 'ACTIVE' AND n.period_end > s.period_end)";

    public record Policy(boolean autoSuspend, int graceDays) {
        public static Policy from(SubscriptionProperties properties) {
            return new Policy(properties.autoSuspendEnabled(), properties.graceDays());
        }
    }

    public record Report(int reminders7d, int reminders1d, int expired, int autoSuspended, int overdueNotSuspended, int failures) {}

    private record Candidate(UUID id, UUID communityId) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformSubscriptionRepository subscriptions;
    private final CommunityRepository communities;
    private final PlatformEmails emails;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public SubscriptionExpiryService(
            NamedParameterJdbcTemplate jdbc,
            PlatformSubscriptionRepository subscriptions,
            CommunityRepository communities,
            PlatformEmails emails,
            AuditService audit,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.communities = communities;
        this.emails = emails;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public Report execute(LocalDate today, Policy policy) {
        int[] counts = new int[6]; // reminders7, reminders1, expired, suspended, warned, failures
        MapSqlParameterSource params = new MapSqlParameterSource("today", today).addValue("plus1", today.plusDays(1)).addValue("plus7", today.plusDays(7));

        for (Candidate c : candidates("s.status = 'ACTIVE' AND s.period_end < :today", params, false)) {
            run(counts, () -> expire(c, today), 2);
        }
        for (Candidate c : candidates("s.status = 'ACTIVE' AND s.period_end BETWEEN :today AND :plus1 AND s.reminder_1d_sent_at IS NULL", params, true)) {
            run(counts, () -> remind(c, today, true), 1);
        }
        for (Candidate c : candidates("s.status = 'ACTIVE' AND s.period_end BETWEEN :today AND :plus7 AND s.reminder_7d_sent_at IS NULL AND s.reminder_1d_sent_at IS NULL", params, true)) {
            run(counts, () -> remind(c, today, false), 0);
        }
        handleOverdueCommunities(today, policy, counts);

        Report report = new Report(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]);
        log.info("Subscription sweep for {}: {}", today, report);
        return report;
    }

    private List<Candidate> candidates(String condition, MapSqlParameterSource params, boolean skipRenewed) {
        return jdbc.query(
                "SELECT s.id, s.community_id FROM platform_subscriptions s WHERE " + condition + (skipRenewed ? NOT_RENEWED : "") + " ORDER BY s.period_end, s.id",
                params,
                (rs, row) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("community_id", UUID.class)));
    }

    private void run(int[] counts, Runnable step, int slot) {
        try {
            step.run();
            counts[slot]++;
        } catch (RuntimeException e) {
            counts[5]++;
            log.error("Subscription sweep step failed: {}", e.toString());
        }
    }

    private void remind(Candidate candidate, LocalDate today, boolean oneDay) {
        transaction.executeWithoutResult(status -> {
            PlatformSubscription s = subscriptions.findByIdAndCommunityId(candidate.id(), candidate.communityId()).orElseThrow();
            long daysLeft = ChronoUnit.DAYS.between(today, s.getPeriodEnd());
            queueOwnerEmail(s, PlatformEmails.SUBSCRIPTION_EXPIRING, daysLeft);
            s.setReminder1dSentAt(oneDay ? clock.instant() : s.getReminder1dSentAt());
            if (s.getReminder7dSentAt() == null) {
                s.setReminder7dSentAt(clock.instant()); // a 1-day reminder also covers the 7-day one
            }
            audit.record("SUBSCRIPTION_REMINDER_SENT", null, s.getCommunityId(), "PlatformSubscription", s.getId(), null,
                    Map.of("kind", oneDay ? "1_DAY" : "7_DAY", "daysLeft", daysLeft));
        });
    }

    private void expire(Candidate candidate, LocalDate today) {
        transaction.executeWithoutResult(status -> {
            PlatformSubscription s = subscriptions.findByIdAndCommunityId(candidate.id(), candidate.communityId()).orElseThrow();
            boolean renewed = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM platform_subscriptions n WHERE n.community_id = :c AND n.status = 'ACTIVE' AND n.period_end > :end)",
                    new MapSqlParameterSource("c", s.getCommunityId()).addValue("end", s.getPeriodEnd()), Boolean.class);
            s.setStatus(SubscriptionStatus.EXPIRED);
            if (!renewed && s.getExpiredNotifiedAt() == null) {
                queueOwnerEmail(s, PlatformEmails.SUBSCRIPTION_EXPIRED, ChronoUnit.DAYS.between(today, s.getPeriodEnd()));
                s.setExpiredNotifiedAt(clock.instant());
            }
            audit.record("SUBSCRIPTION_EXPIRED", null, s.getCommunityId(), "PlatformSubscription", s.getId(),
                    Map.of("status", "ACTIVE"), Map.of("status", "EXPIRED", "renewed", renewed));
        });
    }

    private void queueOwnerEmail(PlatformSubscription s, String template, long daysLeft) {
        Community community = communities.findById(s.getCommunityId()).orElseThrow();
        if (community.getOwner() == null) {
            log.warn("Community {} has no owner to notify about its subscription", community.getId());
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("communityName", community.getName());
        payload.put("planName", s.getPlan().getName());
        payload.put("periodEnd", s.getPeriodEnd().toString());
        payload.put("daysLeft", daysLeft);
        emails.toAddress(template, community.getOwner().getEmail(), payload);
    }

    private void handleOverdueCommunities(LocalDate today, Policy policy, int[] counts) {
        List<UUID> overdue = jdbc.queryForList(
                """
                SELECT c.id FROM communities c
                WHERE c.status = 'ACTIVE'
                  AND EXISTS (SELECT 1 FROM platform_subscriptions s WHERE s.community_id = c.id AND s.status <> 'CANCELLED')
                  AND NOT EXISTS (SELECT 1 FROM platform_subscriptions s WHERE s.community_id = c.id AND s.status <> 'CANCELLED' AND s.period_end >= :today)
                  AND (SELECT max(s.period_end) FROM platform_subscriptions s WHERE s.community_id = c.id AND s.status <> 'CANCELLED') < :cutoff
                ORDER BY c.id
                """,
                new MapSqlParameterSource("today", today).addValue("cutoff", today.minusDays(policy.graceDays())),
                UUID.class);
        if (overdue.isEmpty()) {
            return;
        }
        if (!policy.autoSuspend()) {
            counts[4] += overdue.size();
            log.warn("{} active communities are more than {} days past subscription expiry. Auto-suspension is off, so none were suspended: {}",
                    overdue.size(), policy.graceDays(), overdue);
            return;
        }
        for (UUID id : overdue) {
            run(counts, () -> suspend(id), 3);
        }
    }

    private void suspend(UUID communityId) {
        transaction.executeWithoutResult(status -> {
            Community community = communities.findById(communityId).orElseThrow();
            if (community.getStatus() != CommunityStatus.ACTIVE) {
                return;
            }
            community.setStatus(CommunityStatus.SUSPENDED);
            community.setStatusReason("Subscription expired and the grace period has passed.");
            community.setStatusChangedAt(clock.instant());
            audit.record("COMMUNITY_SUSPENDED", null, communityId, "Community", communityId,
                    Map.of("status", "ACTIVE"), Map.of("status", "SUSPENDED", "reason", "subscription expired (automatic)"));
            if (community.getOwner() != null) {
                emails.toAddress(PlatformEmails.COMMUNITY_SUSPENDED, community.getOwner().getEmail(),
                        Map.of("communityName", community.getName(), "status", "SUSPENDED", "reason", "Your subscription expired and was not renewed."));
            }
        });
    }
}

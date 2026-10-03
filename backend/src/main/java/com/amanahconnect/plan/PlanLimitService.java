package com.amanahconnect.plan;

import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.notification.EmailOutboxRepository;
import com.amanahconnect.notification.EmailStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the limits and features of a community's plan.
 *
 * <p>Each check answers "may this community do <em>one more</em> of these?" and throws a 402 that names
 * the limit and the plan. A missing or null limit means unlimited. Limits are read from the plan on every
 * call, so a super admin changing a plan takes effect immediately.
 */
@Service
@Transactional(readOnly = true)
public class PlanLimitService {

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final CommunityRepository communities;
    private final MemberRepository members;
    private final EmailOutboxRepository outbox;
    private final StorageUsageProvider storage;
    private final Clock clock;

    public PlanLimitService(
            CommunityRepository communities,
            MemberRepository members,
            EmailOutboxRepository outbox,
            StorageUsageProvider storage,
            Clock clock) {
        this.communities = communities;
        this.members = members;
        this.outbox = outbox;
        this.storage = storage;
        this.clock = clock;
    }

    public void checkMemberLimit(UUID communityId) {
        checkMemberLimit(communityId, 1);
    }

    /** @param additional how many members are about to be added (more than one for an import) */
    public void checkMemberLimit(UUID communityId, int additional) {
        PlanSnapshot plan = load(communityId);
        Long limit = limit(plan, PlanLimitKeys.MAX_MEMBERS);
        if (limit == null) {
            return;
        }
        long current = members.countByCommunityIdAndDeletedAtIsNull(communityId);
        if (current + additional > limit) {
            throw new PlanLimitExceededException(
                    "The %s plan allows up to %d members and this community has %d. Upgrade the plan to add more."
                            .formatted(plan.name(), limit, current),
                    PlanLimitKeys.MAX_MEMBERS,
                    limit,
                    current,
                    plan.code());
        }
    }

    /** @param additionalBytes the size of the file about to be stored */
    public void checkStorage(UUID communityId, long additionalBytes) {
        PlanSnapshot plan = load(communityId);
        Long limitMb = limit(plan, PlanLimitKeys.STORAGE_MB);
        if (limitMb == null) {
            return;
        }
        long used = storage.usedBytes(communityId);
        if (used + additionalBytes > limitMb * BYTES_PER_MB) {
            throw new PlanLimitExceededException(
                    "The %s plan includes %d MB of storage and this community uses %d MB. Upgrade the plan or remove files."
                            .formatted(plan.name(), limitMb, used / BYTES_PER_MB),
                    PlanLimitKeys.STORAGE_MB,
                    limitMb,
                    used / BYTES_PER_MB,
                    plan.code());
        }
    }

    public void checkEmailQuota(UUID communityId) {
        checkEmailQuota(communityId, 1);
    }

    /**
     * Emails queued for the community this month and today (India calendar, failed ones not counted) against the plan's
     * {@code emails_per_month} and optional {@code emails_per_day}; both are checked.
     *
     * @param additionalEmails how many emails are about to be queued (a bulk announcement)
     */
    public boolean hasEmailQuota(UUID communityId, int additionalEmails) {
        PlanSnapshot plan = load(communityId);
        return exceeded(communityId, plan, additionalEmails) == null;
    }

    public void checkEmailQuota(UUID communityId, int additionalEmails) {
        PlanSnapshot plan = load(communityId);
        EmailWindow over = exceeded(communityId, plan, additionalEmails);
        if (over != null) {
            throw new PlanLimitExceededException(
                    over.daily
                            ? "The %s plan allows %d emails per day and %d were already queued today. The quota resets tomorrow, or upgrade the plan."
                                    .formatted(plan.name(), over.limit, over.current)
                            : "The %s plan allows %d emails per month and %d were already queued this month. The quota resets next month, or upgrade the plan."
                                    .formatted(plan.name(), over.limit, over.current),
                    over.daily ? PlanLimitKeys.EMAILS_PER_DAY : PlanLimitKeys.EMAILS_PER_MONTH,
                    over.limit,
                    over.current,
                    plan.code());
        }
    }

    /** Emails the community may still queue (the tighter of the day's and the month's allowance); {@link Long#MAX_VALUE} when the plan has no limit. */
    public long emailQuotaRemaining(UUID communityId) {
        PlanSnapshot plan = load(communityId);
        long remaining = Long.MAX_VALUE;
        Long monthly = limit(plan, PlanLimitKeys.EMAILS_PER_MONTH);
        if (monthly != null) remaining = Math.min(remaining, Math.max(0, monthly - queuedSince(communityId, monthStart())));
        Long daily = limit(plan, PlanLimitKeys.EMAILS_PER_DAY);
        if (daily != null) remaining = Math.min(remaining, Math.max(0, daily - queuedSince(communityId, dayStart())));
        return remaining;
    }

    /** What the community has used and may use: for the usage view. A null limit means none. */
    public record EmailUsage(long usedToday, Long dailyLimit, long usedThisMonth, Long monthlyLimit) {}

    public EmailUsage emailUsage(UUID communityId) {
        PlanSnapshot plan = load(communityId);
        return new EmailUsage(queuedSince(communityId, dayStart()), limit(plan, PlanLimitKeys.EMAILS_PER_DAY), queuedSince(communityId, monthStart()), limit(plan, PlanLimitKeys.EMAILS_PER_MONTH));
    }

    private record EmailWindow(boolean daily, long limit, long current) {}

    private EmailWindow exceeded(UUID communityId, PlanSnapshot plan, int additional) {
        Long monthly = limit(plan, PlanLimitKeys.EMAILS_PER_MONTH);
        if (monthly != null) {
            long current = queuedSince(communityId, monthStart());
            if (current + additional > monthly) return new EmailWindow(false, monthly, current);
        }
        Long daily = limit(plan, PlanLimitKeys.EMAILS_PER_DAY);
        if (daily != null) {
            long current = queuedSince(communityId, dayStart());
            if (current + additional > daily) return new EmailWindow(true, daily, current);
        }
        return null;
    }

    private long queuedSince(UUID communityId, Instant since) {
        return outbox.countByCommunityIdAndCreatedAtGreaterThanEqualAndStatusNot(communityId, since, EmailStatus.FAILED);
    }

    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    private Instant monthStart() {
        return java.time.LocalDate.now(clock.withZone(IST)).withDayOfMonth(1).atStartOfDay(IST).toInstant();
    }

    private Instant dayStart() {
        return java.time.LocalDate.now(clock.withZone(IST)).atStartOfDay(IST).toInstant();
    }

    /** Whether the plan includes a feature (absent means no). Never throws. */
    public boolean hasFeature(UUID communityId, String feature) {
        return Boolean.TRUE.equals(load(communityId).features().get(feature));
    }

    /** @throws PlanFeatureUnavailableException unless the plan's features enable it (absent means no) */
    public void requireFeature(UUID communityId, String feature) {
        PlanSnapshot plan = load(communityId);
        if (!Boolean.TRUE.equals(plan.features().get(feature))) {
            throw new PlanFeatureUnavailableException(feature, plan.code(), plan.name());
        }
    }

    /** The community's plan as an immutable copy (never the lazy entity). */
    public PlanSnapshot planOf(UUID communityId) {
        return load(communityId);
    }

    private PlanSnapshot load(UUID communityId) {
        Community community = communities.findById(communityId).orElseThrow(NotFoundException::new);
        return PlanSnapshot.of(community.getPlan());
    }

    private static Long limit(PlanSnapshot plan, String key) {
        Object value = plan.limits().get(key);
        if (value == null) {
            return null; // unlimited
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Plan " + plan.code() + " has a non-numeric limit for " + key);
    }
}

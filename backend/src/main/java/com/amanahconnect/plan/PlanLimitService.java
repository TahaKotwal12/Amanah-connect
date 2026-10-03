package com.amanahconnect.plan;

import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.notification.EmailOutboxRepository;
import com.amanahconnect.notification.EmailStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
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
     * Counts emails queued for this community in the current calendar month (UTC), excluding failed
     * ones.
     *
     * @param additionalEmails how many emails are about to be queued (a bulk announcement)
     */
    /**
     * Whether the community may queue {@code additionalEmails} more this month. Unlike {@link #checkEmailQuota} it never
     * throws, so a caller that merely wants to skip an optional email does not mark its own transaction rollback-only.
     */
    public boolean hasEmailQuota(UUID communityId, int additionalEmails) {
        PlanSnapshot plan = load(communityId);
        Long limit = limit(plan, PlanLimitKeys.EMAILS_PER_MONTH);
        if (limit == null) {
            return true;
        }
        Instant monthStart = YearMonth.now(clock).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        long current = outbox.countByCommunityIdAndCreatedAtGreaterThanEqualAndStatusNot(communityId, monthStart, EmailStatus.FAILED);
        return current + additionalEmails <= limit;
    }

    public void checkEmailQuota(UUID communityId, int additionalEmails) {
        PlanSnapshot plan = load(communityId);
        Long limit = limit(plan, PlanLimitKeys.EMAILS_PER_MONTH);
        if (limit == null) {
            return;
        }
        Instant monthStart = YearMonth.now(clock).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        long current =
                outbox.countByCommunityIdAndCreatedAtGreaterThanEqualAndStatusNot(communityId, monthStart, EmailStatus.FAILED);
        if (current + additionalEmails > limit) {
            throw new PlanLimitExceededException(
                    "The %s plan allows %d emails per month and %d were already queued this month. The quota resets next month, or upgrade the plan."
                            .formatted(plan.name(), limit, current),
                    PlanLimitKeys.EMAILS_PER_MONTH,
                    limit,
                    current,
                    plan.code());
        }
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

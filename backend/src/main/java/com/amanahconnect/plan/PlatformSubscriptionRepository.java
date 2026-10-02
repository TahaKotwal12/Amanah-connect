package com.amanahconnect.plan;

import com.amanahconnect.tenant.TenantRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PlatformSubscriptionRepository extends TenantRepository<PlatformSubscription, UUID> {

    List<PlatformSubscription> findByCommunityIdAndStatusOrderByPeriodEndDesc(
            UUID communityId, SubscriptionStatus status);

    /** Super-admin view across communities: subscriptions ending on or before the given date. */
    List<PlatformSubscription> findByStatusAndPeriodEndLessThanEqualOrderByPeriodEndAsc(
            SubscriptionStatus status, LocalDate periodEnd);
}

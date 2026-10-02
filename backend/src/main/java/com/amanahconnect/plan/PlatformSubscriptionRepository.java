package com.amanahconnect.plan;

import com.amanahconnect.tenant.CrossTenantLookup;
import com.amanahconnect.tenant.TenantRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PlatformSubscriptionRepository extends TenantRepository<PlatformSubscription, UUID> {

    List<PlatformSubscription> findByCommunityIdOrderByPeriodEndDesc(UUID communityId);

    boolean existsByCommunityIdAndReferenceIgnoreCaseAndStatusNot(UUID communityId, String reference, SubscriptionStatus status);

    List<PlatformSubscription> findByCommunityIdAndStatusOrderByPeriodEndDesc(
            UUID communityId, SubscriptionStatus status);

    /** Super-admin view across communities: subscriptions ending on or before the given date. */
    @CrossTenantLookup("Super admin report across communities (expiring subscriptions).")
    List<PlatformSubscription> findByStatusAndPeriodEndLessThanEqualOrderByPeriodEndAsc(
            SubscriptionStatus status, LocalDate periodEnd);
}

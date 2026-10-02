package com.amanahconnect.billing;

import com.amanahconnect.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;

public interface FeePlanRepository extends TenantRepository<FeePlan, UUID> {

    List<FeePlan> findByCommunityIdAndActiveTrue(UUID communityId);
}

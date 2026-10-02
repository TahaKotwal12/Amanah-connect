package com.amanahconnect.community;

import com.amanahconnect.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;

public interface CommunityUserRepository extends TenantRepository<CommunityUser, UUID> {

    /**
     * Resolves which communities the authenticated user administers. This is the single sanctioned
     * lookup that starts from the user rather than from a community, used to build the tenant context.
     */
    List<CommunityUser> findByUserId(UUID userId);

    List<CommunityUser> findByCommunityId(UUID communityId);
}

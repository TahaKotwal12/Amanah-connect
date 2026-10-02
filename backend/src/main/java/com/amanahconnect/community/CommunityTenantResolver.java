package com.amanahconnect.community;

import com.amanahconnect.tenant.CurrentTenant;
import com.amanahconnect.tenant.TenantResolver;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the community of a COMMUNITY_ADMIN through community_users. */
@Service
@Transactional(readOnly = true)
public class CommunityTenantResolver implements TenantResolver {

    private final CommunityUserRepository communityUsers;
    private final CommunityRepository communities;

    public CommunityTenantResolver(CommunityUserRepository communityUsers, CommunityRepository communities) {
        this.communityUsers = communityUsers;
        this.communities = communities;
    }

    @Override
    public Optional<CurrentTenant> resolve(UUID userId) {
        return communityUsers.findByUserId(userId).stream()
                .min(Comparator.comparing(link -> link.getRole() == CommunityRole.OWNER ? 0 : 1))
                .flatMap(link -> communities.findById(link.getCommunityId()))
                .map(
                        community -> {
                            boolean writable =
                                    community.getStatus() != CommunityStatus.SUSPENDED
                                            && community.getStatus() != CommunityStatus.ARCHIVED;
                            return new CurrentTenant(community.getId(), userId, community.getStatus().name(), writable);
                        });
    }
}

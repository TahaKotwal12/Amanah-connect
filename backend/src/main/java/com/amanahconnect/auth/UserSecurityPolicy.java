package com.amanahconnect.auth;

import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.community.CommunityUser;
import com.amanahconnect.community.CommunityUserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Who must use 2FA, and which community a user administers. */
@Component
public class UserSecurityPolicy {

    private static final String REQUIRE_2FA_SETTING = "require_2fa";

    private final CommunityUserRepository communityUsers;
    private final CommunityRepository communities;

    public UserSecurityPolicy(CommunityUserRepository communityUsers, CommunityRepository communities) {
        this.communityUsers = communityUsers;
        this.communities = communities;
    }

    /** The community this user administers (the first, if ever several), or empty for a super admin. */
    public Optional<Community> communityOf(User user) {
        if (user.getRole() != UserRole.COMMUNITY_ADMIN) {
            return Optional.empty();
        }
        List<CommunityUser> links = communityUsers.findByUserId(user.getId());
        return links.isEmpty() ? Optional.empty() : communities.findById(links.get(0).getCommunityId());
    }

    public UUID communityIdOf(User user) {
        return communityOf(user).map(Community::getId).orElse(null);
    }

    /** 2FA cannot be turned off: always for SUPER_ADMIN, and when the community requires it. */
    public boolean isMfaMandatory(User user) {
        if (user.getRole() == UserRole.SUPER_ADMIN) {
            return true;
        }
        return communityOf(user)
                .map(c -> Boolean.TRUE.equals(c.getSettings().get(REQUIRE_2FA_SETTING)))
                .orElse(false);
    }

    /** True when the user must enrol in 2FA before using anything else. */
    public boolean mfaSetupRequired(User user) {
        return !user.isTotpEnabled() && (user.isMustSetup2fa() || isMfaMandatory(user));
    }
}

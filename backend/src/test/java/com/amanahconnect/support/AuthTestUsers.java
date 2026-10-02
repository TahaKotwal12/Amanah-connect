package com.amanahconnect.support;

import com.amanahconnect.auth.User;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.auth.UserRole;
import com.amanahconnect.auth.UserStatus;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRole;
import com.amanahconnect.community.CommunityUser;
import com.amanahconnect.community.CommunityUserRepository;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates sign-in-ready users for the auth tests. */
@Component
@Transactional
public class AuthTestUsers {

    /** Satisfies the password policy and contains none of the generated email names. */
    public static final String PASSWORD = "Correct-Horse-9-Staple";

    public record TestUser(UUID id, String email, String password) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TestData data;
    private final CommunityUserRepository communityUsers;

    public AuthTestUsers(UserRepository users, PasswordEncoder encoder, TestData data, CommunityUserRepository communityUsers) {
        this.users = users;
        this.encoder = encoder;
        this.data = data;
        this.communityUsers = communityUsers;
    }

    public TestUser superAdmin() {
        return create(UserRole.SUPER_ADMIN, UserStatus.ACTIVE);
    }

    /** A community admin who owns a brand-new community. */
    public TestUser communityAdmin() {
        return communityAdminOf(data.community());
    }

    public TestUser communityAdminOf(Community community) {
        TestUser user = create(UserRole.COMMUNITY_ADMIN, UserStatus.ACTIVE);
        CommunityUser link = new CommunityUser();
        link.setCommunityId(community.getId());
        link.setUser(users.getReferenceById(user.id()));
        link.setRole(CommunityRole.OWNER);
        communityUsers.save(link);
        return user;
    }

    public TestUser create(UserRole role, UserStatus status) {
        User user = new User();
        user.setEmail("auth-" + TestData.unique() + "@example.test");
        user.setFullName("Auth Test " + role);
        user.setRole(role);
        user.setStatus(status);
        if (status == UserStatus.ACTIVE || status == UserStatus.DISABLED) {
            user.setPasswordHash(encoder.encode(PASSWORD));
        }
        users.save(user);
        return new TestUser(user.getId(), user.getEmail(), PASSWORD);
    }
}

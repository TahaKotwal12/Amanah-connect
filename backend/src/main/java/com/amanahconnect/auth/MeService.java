package com.amanahconnect.auth;

import com.amanahconnect.auth.web.AuthDtos.CommunitySummary;
import com.amanahconnect.auth.web.AuthDtos.Flags;
import com.amanahconnect.auth.web.AuthDtos.MeResponse;
import com.amanahconnect.auth.web.AuthDtos.UserSummary;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the response for GET /auth/me. */
@Service
@Transactional(readOnly = true)
public class MeService {

    private final UserRepository users;
    private final UserSecurityPolicy policy;

    public MeService(UserRepository users, UserSecurityPolicy policy) {
        this.users = users;
        this.policy = policy;
    }

    public MeResponse describe(UUID userId) {
        User user =
                users.findById(userId)
                        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required."));
        CommunitySummary community =
                policy.communityOf(user)
                        .map(c -> new CommunitySummary(c.getId(), c.getName(), c.getSlug(), c.getStatus().name()))
                        .orElse(null);
        return new MeResponse(
                new UserSummary(
                        user.getId(), user.getEmail(), user.getFullName(), user.getRole().name(), user.getStatus().name()),
                community,
                new Flags(user.isTotpEnabled(), policy.mfaSetupRequired(user), policy.isMfaMandatory(user)));
    }
}

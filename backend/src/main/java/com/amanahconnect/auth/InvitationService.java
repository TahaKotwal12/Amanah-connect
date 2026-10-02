package com.amanahconnect.auth;

import com.amanahconnect.audit.AuditAction;
import com.amanahconnect.audit.AuditService;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sends (or re-sends) the invitation to a user created by a super admin. Used by community onboarding. */
@Service
@Transactional
public class InvitationService {

    private final PasswordService passwords;
    private final AuthTokenRepository authTokens;
    private final AuthEmails emails;
    private final UserSecurityPolicy policy;
    private final AuthProperties properties;
    private final AuditService audit;
    private final Clock clock;

    public InvitationService(
            PasswordService passwords,
            AuthTokenRepository authTokens,
            AuthEmails emails,
            UserSecurityPolicy policy,
            AuthProperties properties,
            AuditService audit,
            Clock clock) {
        this.passwords = passwords;
        this.authTokens = authTokens;
        this.emails = emails;
        this.policy = policy;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    /** The invitee must already exist with status INVITED. Earlier unused invitations stop working. */
    public void invite(User invitee, UUID invitedBy) {
        if (invitee.getStatus() != UserStatus.INVITED) {
            throw new IllegalArgumentException("Only users with status INVITED can be invited");
        }
        authTokens.invalidateUnused(invitee.getId(), AuthTokenPurpose.INVITATION, clock.instant());
        String raw = passwords.issueToken(invitee, AuthTokenPurpose.INVITATION, properties.inviteTtl());
        emails.invitation(invitee, raw, properties.inviteTtl());
        audit.record(
                AuditAction.INVITATION_CREATED,
                invitedBy,
                policy.communityIdOf(invitee),
                "User",
                invitee.getId(),
                null,
                Map.of("expiresInHours", properties.inviteTtl().toHours()));
    }
}

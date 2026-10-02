package com.amanahconnect.auth;

import com.amanahconnect.audit.AuditAction;
import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.Masking;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Forgot / reset / change password and accepting an invitation. */
@Service
@Transactional(noRollbackFor = ApiException.class)
public class PasswordService {

    private static final int MAX_RESET_EMAILS_PER_HOUR = 3;

    private final UserRepository users;
    private final AuthTokenRepository authTokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy policy;
    private final RefreshTokenService refreshTokens;
    private final LockoutService lockout;
    private final UserSecurityPolicy securityPolicy;
    private final AuthEmails emails;
    private final AuthProperties properties;
    private final AuthAudit audit;
    private final AuditService rawAudit;
    private final Clock clock;

    public PasswordService(
            UserRepository users,
            AuthTokenRepository authTokens,
            PasswordEncoder passwordEncoder,
            PasswordPolicy policy,
            RefreshTokenService refreshTokens,
            LockoutService lockout,
            UserSecurityPolicy securityPolicy,
            AuthEmails emails,
            AuthProperties properties,
            AuthAudit audit,
            AuditService rawAudit,
            Clock clock) {
        this.users = users;
        this.authTokens = authTokens;
        this.passwordEncoder = passwordEncoder;
        this.policy = policy;
        this.refreshTokens = refreshTokens;
        this.lockout = lockout;
        this.securityPolicy = securityPolicy;
        this.emails = emails;
        this.properties = properties;
        this.audit = audit;
        this.rawAudit = rawAudit;
        this.clock = clock;
    }

    /**
     * Always completes the same way whether or not the email has an account; the caller answers 202
     * either way. Both branches write one audit row, and the unknown branch does a comparable amount
     * of hashing, to keep response time from leaking which addresses exist.
     */
    public void requestReset(String email) {
        String normalized = email.trim();
        Optional<User> found = users.findByEmail(normalized).filter(u -> u.getStatus() == UserStatus.ACTIVE);
        if (found.isEmpty()) {
            Tokens.sha256Hex(Tokens.newOpaqueToken());
            rawAudit.record(
                    AuditAction.PASSWORD_RESET_REQUESTED,
                    null,
                    null,
                    "User",
                    null,
                    null,
                    Map.of("attemptedEmail", Masking.email(normalized), "accountFound", false));
            return;
        }
        User user = found.get();
        Instant now = clock.instant();
        long recent =
                authTokens.countByUserIdAndPurposeAndCreatedAtAfter(
                        user.getId(), AuthTokenPurpose.PASSWORD_RESET, now.minus(Duration.ofHours(1)));
        if (recent >= MAX_RESET_EMAILS_PER_HOUR) {
            // Silently stop: do not let someone flood a victim's inbox, and do not reveal the cap.
            Tokens.sha256Hex(Tokens.newOpaqueToken());
            audit.event(AuditAction.PASSWORD_RESET_REQUESTED, user, Map.of("suppressed", true));
            return;
        }
        String raw = issueToken(user, AuthTokenPurpose.PASSWORD_RESET, properties.resetTtl());
        emails.passwordReset(user, raw, properties.resetTtl());
        audit.event(AuditAction.PASSWORD_RESET_REQUESTED, user, Map.of());
    }

    public void resetPassword(String rawToken, String newPassword) {
        AuthToken token = usableToken(rawToken, AuthTokenPurpose.PASSWORD_RESET);
        UUID userId = token.getUser().getId();
        String email = token.getUser().getEmail();
        policy.validate(newPassword, email); // before consuming, so a weak password does not burn the link
        consume(token);

        User user = users.findByIdForUpdate(userId).orElseThrow(PasswordService::invalidToken);
        applyNewPassword(user, newPassword);
        lockout.registerSuccess(user);
        authTokens.invalidateUnused(userId, AuthTokenPurpose.PASSWORD_RESET, clock.instant());
        refreshTokens.revokeAll(userId);
        audit.event(AuditAction.PASSWORD_RESET_COMPLETED, user, Map.of());
    }

    /** @return whether the user must now enrol in 2FA */
    public boolean acceptInvitation(String rawToken, String newPassword) {
        AuthToken token = usableToken(rawToken, AuthTokenPurpose.INVITATION);
        UUID userId = token.getUser().getId();
        policy.validate(newPassword, token.getUser().getEmail());
        consume(token);

        User user = users.findByIdForUpdate(userId).orElseThrow(PasswordService::invalidToken);
        if (user.getStatus() != UserStatus.INVITED) {
            throw invalidToken();
        }
        applyNewPassword(user, newPassword);
        user.setStatus(UserStatus.ACTIVE);
        audit.event(AuditAction.INVITATION_ACCEPTED, user, Map.of());
        return securityPolicy.mfaSetupRequired(user);
    }

    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user =
                users.findByIdForUpdate(userId)
                        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required."));
        if (lockout.isLocked(user)) {
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED, "Too many failed attempts. Try again later.");
        }
        lockout.clearIfExpired(user);
        if (currentPassword == null
                || user.getPasswordHash() == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            lockout.registerFailure(user, AuditAction.LOGIN_FAILED);
            throw new ApiException(ErrorCode.INVALID_CURRENT_PASSWORD, "The current password is incorrect.");
        }
        policy.validate(newPassword, user.getEmail());
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ApiException(
                    ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "The new password must differ from the current one.",
                    java.util.List.of("Choose a password you have not used here."));
        }
        applyNewPassword(user, newPassword);
        lockout.registerSuccess(user);
        refreshTokens.revokeAll(userId);
        audit.event(AuditAction.PASSWORD_CHANGED, user, Map.of());
    }

    /** Creates a single-use token (hash stored, raw returned once) and retires older unused ones of the same purpose. */
    String issueToken(User user, AuthTokenPurpose purpose, Duration validFor) {
        String raw = Tokens.newOpaqueToken();
        AuthToken token = new AuthToken();
        token.setUser(user);
        token.setPurpose(purpose);
        token.setTokenHash(Tokens.sha256Hex(raw));
        token.setExpiresAt(clock.instant().plus(validFor));
        authTokens.save(token);
        return raw;
    }

    private AuthToken usableToken(String rawToken, AuthTokenPurpose purpose) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidToken();
        }
        AuthToken token =
                authTokens
                        .findByTokenHashAndPurpose(Tokens.sha256Hex(rawToken.trim()), purpose)
                        .orElseThrow(PasswordService::invalidToken);
        if (token.getUsedAt() != null || !token.getExpiresAt().isAfter(clock.instant())) {
            throw invalidToken();
        }
        return token;
    }

    /** Atomic: exactly one concurrent caller wins, so a link can never be used twice. */
    private void consume(AuthToken token) {
        if (authTokens.consume(token.getId(), clock.instant()) != 1) {
            throw invalidToken();
        }
    }

    private void applyNewPassword(User user, String newPassword) {
        user.setPasswordHash(passwordEncoder.encode(newPassword));
    }

    private static ApiException invalidToken() {
        return new ApiException(ErrorCode.INVALID_TOKEN, "This link is invalid or has expired.");
    }
}

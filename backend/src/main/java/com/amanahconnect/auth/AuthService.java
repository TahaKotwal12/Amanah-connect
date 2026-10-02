package com.amanahconnect.auth;

import com.amanahconnect.audit.AuditAction;
import com.amanahconnect.auth.ratelimit.GhostLockout;
import com.amanahconnect.auth.ratelimit.RateLimitProperties;
import com.amanahconnect.auth.ratelimit.RateLimitService;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.web.RequestInfo;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in, second factor, refresh and sign-out.
 *
 * <p>Every method keeps its writes when it ends in an {@link ApiException} ({@code noRollbackFor}):
 * a failed login must still count toward the lockout and still be audited.
 *
 * <p>Account-existence is never revealed: an unknown email and a wrong password produce the same
 * response with similar timing (a dummy BCrypt check runs), and unknown emails lock out after the same
 * number of attempts as real ones.
 */
@Service
@Transactional(noRollbackFor = ApiException.class)
public class AuthService {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final TwoFactorService twoFactor;
    private final LockoutService lockout;
    private final GhostLockout ghostLockout;
    private final RateLimitService rateLimiter;
    private final RateLimitProperties limits;
    private final UserSecurityPolicy policy;
    private final AuthProperties properties;
    private final AuthAudit audit;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            JwtService jwt,
            RefreshTokenService refreshTokens,
            TwoFactorService twoFactor,
            LockoutService lockout,
            GhostLockout ghostLockout,
            RateLimitService rateLimiter,
            RateLimitProperties limits,
            UserSecurityPolicy policy,
            AuthProperties properties,
            AuthAudit audit,
            Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.twoFactor = twoFactor;
        this.lockout = lockout;
        this.ghostLockout = ghostLockout;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
        this.policy = policy;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
        // Same cost factor as real hashes, so the unknown-email path takes about as long.
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginOutcome login(String email, String password, RequestInfo info) {
        String normalized = email.trim();
        rateLimiter.consume("login-email:" + normalized.toLowerCase(Locale.ROOT), limits.loginPerEmailPerMinute(), ONE_MINUTE);

        Optional<User> found = users.findByEmailForUpdate(normalized);
        if (found.isEmpty()) {
            return failUnknown(normalized, password);
        }
        User user = found.get();
        if (lockout.isLocked(user)) {
            passwordEncoder.matches(password, dummyHash); // keep timing uniform
            throw locked();
        }
        lockout.clearIfExpired(user);

        String hash = user.getPasswordHash() == null ? dummyHash : user.getPasswordHash();
        boolean passwordOk = passwordEncoder.matches(password, hash);
        if (!passwordOk || user.getStatus() != UserStatus.ACTIVE || user.getPasswordHash() == null) {
            lockout.registerFailure(user, AuditAction.LOGIN_FAILED);
            throw invalidCredentials();
        }

        if (user.isTotpEnabled()) {
            // Password is right but the account is not signed in yet: no failure counted, nothing issued.
            return new LoginOutcome.MfaChallenge(jwt.issueMfaToken(user), properties.mfaTtl().toSeconds());
        }
        lockout.registerSuccess(user);
        return complete(user, info);
    }

    public LoginOutcome loginWithSecondFactor(String mfaToken, String code, String recoveryCode, RequestInfo info) {
        UUID userId = jwt.parseMfaToken(mfaToken);
        User user =
                users.findByIdForUpdate(userId)
                        .filter(u -> u.getStatus() == UserStatus.ACTIVE && u.isTotpEnabled())
                        .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TOKEN, "The verification session is invalid or has expired. Sign in again."));
        if (lockout.isLocked(user)) {
            throw locked();
        }
        lockout.clearIfExpired(user);
        if (!twoFactor.verifySecondFactor(user, code, recoveryCode)) {
            lockout.registerFailure(user, AuditAction.MFA_FAILED);
            throw new ApiException(ErrorCode.INVALID_MFA_CODE, "The verification code is incorrect.");
        }
        lockout.registerSuccess(user);
        return complete(user, info);
    }

    public LoginOutcome refresh(String rawRefreshToken, RequestInfo info) {
        RefreshTokenService.Rotated rotated = refreshTokens.rotate(rawRefreshToken, info);
        User user = rotated.user();
        // Re-evaluated on every refresh, so enrolling in 2FA clears the setup-required flag.
        boolean setupRequired = policy.mfaSetupRequired(user);
        return new LoginOutcome.Authenticated(
                jwt.issueAccessToken(user, setupRequired),
                properties.accessTtl().toSeconds(),
                setupRequired,
                rotated.newToken());
    }

    public void logout(String rawRefreshToken) {
        refreshTokens.revokeFamilyOf(rawRefreshToken, true);
    }

    public void logoutAll(UUID userId) {
        users.findById(userId)
                .ifPresent(
                        user -> {
                            int revoked = refreshTokens.revokeAll(userId);
                            audit.event(AuditAction.LOGOUT_ALL, user, Map.of("revokedTokens", revoked));
                        });
    }

    private LoginOutcome.Authenticated complete(User user, RequestInfo info) {
        user.setLastLoginAt(clock.instant());
        boolean setupRequired = policy.mfaSetupRequired(user);
        String refresh = refreshTokens.start(user, info);
        audit.event(AuditAction.LOGIN_SUCCESS, user, Map.of("mfaSetupRequired", setupRequired));
        return new LoginOutcome.Authenticated(
                jwt.issueAccessToken(user, setupRequired),
                properties.accessTtl().toSeconds(),
                setupRequired,
                refresh);
    }

    private LoginOutcome failUnknown(String email, String password) {
        passwordEncoder.matches(password, dummyHash);
        if (ghostLockout.isLocked(email, properties.lockDuration())) {
            audit.unknownAccount(AuditAction.LOGIN_FAILED, email);
            throw locked();
        }
        ghostLockout.recordFailure(email, properties.maxFailedAttempts(), properties.lockDuration());
        audit.unknownAccount(AuditAction.LOGIN_FAILED, email);
        throw invalidCredentials();
    }

    private static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password.");
    }

    private static ApiException locked() {
        return new ApiException(ErrorCode.ACCOUNT_LOCKED, "Too many failed attempts. Try again in a few minutes.");
    }
}

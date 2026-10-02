package com.amanahconnect.auth;

import com.amanahconnect.audit.AuditAction;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.web.RequestInfo;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rotating opaque refresh tokens with reuse detection.
 *
 * <p>Each token is 256 random bits, stored only as a SHA-256 hash, and belongs to a family (one per
 * login). Using a token revokes it and issues its successor in the same family. If a token that was
 * already used or revoked is presented again, someone is replaying a stolen copy, so the whole family
 * is revoked and both the thief and the real user must sign in again.
 */
@Service
@Transactional(noRollbackFor = ApiException.class)
public class RefreshTokenService {

    public record Rotated(User user, String newToken) {}

    private final RefreshTokenRepository tokens;
    private final AuthProperties properties;
    private final Clock clock;
    private final AuthAudit audit;

    public RefreshTokenService(
            RefreshTokenRepository tokens, AuthProperties properties, Clock clock, AuthAudit audit) {
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
    }

    /** Starts a new family for a fresh login and returns the raw token (shown once, only as a cookie). */
    public String start(User user, RequestInfo info) {
        return issue(user, UUID.randomUUID(), info);
    }

    public Rotated rotate(String rawToken, RequestInfo info) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalid();
        }
        RefreshToken current =
                tokens.findByTokenHashForUpdate(Tokens.sha256Hex(rawToken)).orElseThrow(RefreshTokenService::invalid);
        Instant now = clock.instant();
        User user = current.getUser();
        Hibernate.initialize(user);
        UUID familyId = current.getFamilyId();

        if (current.getRevokedAt() != null) {
            tokens.revokeFamily(familyId, now);
            audit.event(AuditAction.TOKEN_REUSE_DETECTED, user, Map.of("familyId", familyId.toString()));
            throw invalid();
        }
        if (!current.getExpiresAt().isAfter(now) || user.getStatus() != UserStatus.ACTIVE) {
            tokens.revokeFamily(familyId, now);
            throw invalid();
        }

        RefreshToken next = newToken(user, familyId, info);
        String raw = Tokens.newOpaqueToken();
        next.setTokenHash(Tokens.sha256Hex(raw));
        tokens.save(next);
        current.setRevokedAt(now);
        current.setReplacedBy(next.getId());
        return new Rotated(user, raw);
    }

    /** Revokes every token in the family of the presented token. Silent if the token is unknown. */
    public void revokeFamilyOf(String rawToken, boolean audited) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokens.findByTokenHash(Tokens.sha256Hex(rawToken))
                .ifPresent(
                        token -> {
                            User user = token.getUser();
                            Hibernate.initialize(user);
                            tokens.revokeFamily(token.getFamilyId(), clock.instant());
                            if (audited) {
                                audit.event(AuditAction.LOGOUT, user, Map.of());
                            }
                        });
    }

    /** Signs the user out everywhere. Used on password change/reset and "log out of all devices". */
    public int revokeAll(UUID userId) {
        return tokens.revokeAllForUser(userId, clock.instant());
    }

    private String issue(User user, UUID familyId, RequestInfo info) {
        RefreshToken token = newToken(user, familyId, info);
        String raw = Tokens.newOpaqueToken();
        token.setTokenHash(Tokens.sha256Hex(raw));
        tokens.save(token);
        return raw;
    }

    private RefreshToken newToken(User user, UUID familyId, RequestInfo info) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setFamilyId(familyId);
        token.setExpiresAt(clock.instant().plus(properties.refreshTtl())); // sliding: each rotation extends it
        token.setIp(info.ip());
        token.setUserAgent(info.userAgent());
        return token;
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "The session is invalid or has expired. Sign in again.");
    }
}

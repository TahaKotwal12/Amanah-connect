package com.amanahconnect.auth;

import com.amanahconnect.audit.AuditAction;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Five consecutive failures lock the account for 15 minutes. Callers hold the user's row lock, so
 * concurrent guesses are serialised and none is lost.
 */
@Component
public class LockoutService {

    private final AuthProperties properties;
    private final Clock clock;
    private final AuthAudit audit;

    public LockoutService(AuthProperties properties, Clock clock, AuthAudit audit) {
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
    }

    public boolean isLocked(User user) {
        Instant lockedUntil = user.getLockedUntil();
        return lockedUntil != null && lockedUntil.isAfter(clock.instant());
    }

    /** Counts one failure, locking the account on the configured threshold. */
    public void registerFailure(User user, String failureAction) {
        Instant now = clock.instant();
        if (user.getLockedUntil() != null && !user.getLockedUntil().isAfter(now)) {
            user.setFailedAttempts(0); // an expired lock starts a fresh count
            user.setLockedUntil(null);
        }
        user.setFailedAttempts(user.getFailedAttempts() + 1);
        audit.event(failureAction, user, Map.of("failedAttempts", user.getFailedAttempts()));
        if (user.getFailedAttempts() >= properties.maxFailedAttempts()) {
            user.setLockedUntil(now.plus(properties.lockDuration()));
            audit.event(
                    AuditAction.ACCOUNT_LOCKED,
                    user,
                    Map.of("lockedUntil", user.getLockedUntil().toString()));
        }
    }

    public void registerSuccess(User user) {
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
    }

    /** Clears a lock that has run out, so a stale count does not carry into the next attempt. */
    public void clearIfExpired(User user) {
        if (user.getLockedUntil() != null && !user.getLockedUntil().isAfter(clock.instant())) {
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
        }
    }
}

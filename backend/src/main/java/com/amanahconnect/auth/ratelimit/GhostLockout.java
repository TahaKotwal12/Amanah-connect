package com.amanahconnect.auth.ratelimit;

import com.amanahconnect.auth.Tokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Applies the same "5 failures lock it for 15 minutes" behaviour to email addresses that have no
 * account.
 *
 * <p>Without this, an attacker could tell real accounts from unknown ones: after five bad passwords a
 * real account answers "locked" while an unknown address keeps answering "invalid credentials". Real
 * accounts keep their lock in the database; this class only mirrors the behaviour for strangers, in
 * memory, keyed by a hash so no address is retained. The map is bounded; when full it stops tracking
 * new addresses, which fails open (the per-IP and per-email rate limits still apply).
 */
@Component
public class GhostLockout {

    private static final int MAX_ENTRIES = 20_000;

    private record State(int failures, Instant lockedUntil, Instant lastFailure) {}

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public GhostLockout(Clock clock) {
        this.clock = clock;
    }

    public boolean isLocked(String email, Duration window) {
        State state = states.get(key(email));
        if (state == null) {
            return false;
        }
        Instant now = clock.instant();
        if (state.lockedUntil() != null && state.lockedUntil().isAfter(now)) {
            return true;
        }
        if (state.lastFailure().plus(window).isBefore(now)) {
            states.remove(key(email));
        }
        return false;
    }

    public void recordFailure(String email, int maxFailures, Duration lockDuration) {
        Instant now = clock.instant();
        if (states.size() >= MAX_ENTRIES) {
            states.values().removeIf(s -> s.lastFailure().plus(lockDuration).isBefore(now));
            if (states.size() >= MAX_ENTRIES) {
                return;
            }
        }
        states.merge(
                key(email),
                new State(1, null, now),
                (old, ignored) -> {
                    boolean expired = old.lastFailure().plus(lockDuration).isBefore(now);
                    int failures = expired ? 1 : old.failures() + 1;
                    Instant lockedUntil = failures >= maxFailures ? now.plus(lockDuration) : null;
                    return new State(failures, lockedUntil, now);
                });
    }

    private static String key(String email) {
        return Tokens.sha256Hex(email.trim().toLowerCase(java.util.Locale.ROOT));
    }
}

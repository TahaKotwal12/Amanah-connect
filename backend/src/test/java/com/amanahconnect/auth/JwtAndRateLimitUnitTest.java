package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.auth.crypto.AuthKeys;
import com.amanahconnect.auth.ratelimit.GhostLockout;
import com.amanahconnect.auth.ratelimit.RateLimitService;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtAndRateLimitUnitTest {

    private static final AuthProperties PROPERTIES =
            new AuthProperties(null, null, "http://localhost", "amanah-connect", Duration.ofMinutes(15), Duration.ofMinutes(5),
                    Duration.ofDays(7), Duration.ofMinutes(30), Duration.ofHours(48), 4, 5, Duration.ofMinutes(15));
    private static final AuthKeys KEYS = new AuthKeys(new SecretKeySpec("k".repeat(48).getBytes(), "HmacSHA256"), new SecretKeySpec(new byte[32], "AES"));

    private static User user(UserRole role) {
        User user = new User();
        try {
            var id = com.amanahconnect.common.persistence.BaseEntity.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(user, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        user.setRole(role);
        return user;
    }

    // ---- JWT --------------------------------------------------------------------------------

    @Test
    void accessAndMfaTokensAreNotInterchangeable() {
        JwtService jwt = new JwtService(PROPERTIES, KEYS, Clock.systemUTC());
        User user = user(UserRole.COMMUNITY_ADMIN);
        String access = jwt.issueAccessToken(user, false);
        String mfa = jwt.issueMfaToken(user);

        assertThat(jwt.accessTokenDecoder().decode(access).getSubject()).isEqualTo(user.getId().toString());
        assertThatThrownBy(() -> jwt.accessTokenDecoder().decode(mfa)).isInstanceOf(JwtException.class);
        assertThat(jwt.parseMfaToken(mfa)).isEqualTo(user.getId());
        assertThatThrownBy(() -> jwt.parseMfaToken(access)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_TOKEN));
    }

    @Test
    void tokensCarryTheDocumentedClaimsAndLifetimes() {
        Clock fixed = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        JwtService jwt = new JwtService(PROPERTIES, KEYS, fixed);
        User admin = user(UserRole.SUPER_ADMIN);

        Jwt access = jwt.accessTokenDecoder().decode(jwt.issueAccessToken(admin, true));

        assertThat(access.getClaimAsString("role")).isEqualTo("SUPER_ADMIN");
        assertThat(access.getClaimAsBoolean("mfa_setup_required")).isTrue();
        assertThat(access.getClaimAsString("typ")).isEqualTo("access");
        assertThat(access.getClaimAsString("iss")).isEqualTo("amanah-connect");
        assertThat(Duration.between(access.getIssuedAt(), access.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(access.getId()).isNotBlank();
        assertThat(new JwtService(PROPERTIES, KEYS, fixed).issueAccessToken(admin, true)).as("jti makes every token unique").isNotEqualTo(jwt.issueAccessToken(admin, true));
    }

    @Test
    void expiredTokensAreRejected() {
        JwtService old = new JwtService(PROPERTIES, KEYS, Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));
        JwtService now = new JwtService(PROPERTIES, KEYS, Clock.systemUTC());
        User user = user(UserRole.COMMUNITY_ADMIN);

        assertThatThrownBy(() -> now.accessTokenDecoder().decode(old.issueAccessToken(user, false))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> now.parseMfaToken(old.issueMfaToken(user))).isInstanceOf(ApiException.class);
    }

    // ---- rate limiter -----------------------------------------------------------------------

    @Test
    void allowsUpToTheCapacityThenRefusesWithRetryAfter() {
        RateLimitService limiter = new RateLimitService();

        limiter.consume("k", 3, Duration.ofMinutes(1));
        limiter.consume("k", 3, Duration.ofMinutes(1));
        limiter.consume("k", 3, Duration.ofMinutes(1));

        assertThatThrownBy(() -> limiter.consume("k", 3, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(e.retryAfterSeconds()).isBetween(1L, 60L);
                });
    }

    @Test
    void keysAreIndependent() {
        RateLimitService limiter = new RateLimitService();
        limiter.consume("a", 1, Duration.ofHours(1));

        limiter.consume("b", 1, Duration.ofHours(1)); // a different key has its own bucket

        assertThatThrownBy(() -> limiter.consume("a", 1, Duration.ofHours(1))).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void tokensRefillOverTime() throws Exception {
        RateLimitService limiter = new RateLimitService();
        limiter.consume("slow", 1, Duration.ofMillis(300));
        assertThatThrownBy(() -> limiter.consume("slow", 1, Duration.ofMillis(300))).isInstanceOf(RateLimitedException.class);

        Thread.sleep(400);

        limiter.consume("slow", 1, Duration.ofMillis(300));
    }

    @Test
    void stressedWithManyDistinctKeysItStaysBoundedAndKeepsWorking() {
        RateLimitService limiter = new RateLimitService();
        for (int i = 0; i < 60_000; i++) {
            limiter.consume("flood-" + i, 5, Duration.ofMinutes(1));
        }

        limiter.consume("after-flood", 5, Duration.ofMinutes(1));
    }

    // ---- ghost lockout ----------------------------------------------------------------------

    @Test
    void unknownAddressesLockAfterFiveFailuresForFifteenMinutes() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        GhostLockout ghost = new GhostLockout(clock);
        Duration lock = Duration.ofMinutes(15);

        for (int i = 0; i < 4; i++) {
            ghost.recordFailure("Ghost@Example.test", 5, lock);
            assertThat(ghost.isLocked("ghost@example.test", lock)).as("after %d failures", i + 1).isFalse();
        }
        ghost.recordFailure("ghost@example.test", 5, lock);

        assertThat(ghost.isLocked(" GHOST@example.test ", lock)).as("case and spaces do not matter").isTrue();
        assertThat(ghost.isLocked("other@example.test", lock)).isFalse();

        clock.set(start.plus(Duration.ofMinutes(16)));
        assertThat(ghost.isLocked("ghost@example.test", lock)).as("expires like a real lock").isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

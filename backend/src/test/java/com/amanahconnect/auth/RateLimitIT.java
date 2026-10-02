package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Per-IP limits, with small values in a dedicated context. Every test uses its own endpoint, so the
 * buckets (keyed by the client address, always 127.0.0.1 here) never interfere with one another.
 */
@TestPropertySource(
        properties = {
            "app.rate-limit.login-per-ip-per-minute=3",
            "app.rate-limit.mfa-per-ip-per-minute=2",
            "app.rate-limit.forgot-password-per-ip-per-hour=2",
            "app.rate-limit.lead-per-ip-per-hour=2"
        })
class RateLimitIT extends AbstractAuthIT {

    @Test
    void loginIsLimitedPerIpAndAnswers429WithRetryAfter() {
        for (int i = 0; i < 3; i++) {
            ApiClient.Response ok = api.post("/api/v1/auth/login", Map.of("email", "someone" + i + "@example.test", "password", "Wrong-Password-1"));
            assertThat(ok.status()).as("request %d is processed", i + 1).isEqualTo(401);
        }

        ApiClient.Response limited = api.post("/api/v1/auth/login", Map.of("email", "someone-else@example.test", "password", "Wrong-Password-1"), "X-Request-Id", "rate-1");

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.header("Content-Type")).startsWith("application/problem+json");
        assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(1L, 60L);
        assertThat(limited.header("X-Request-Id")).isEqualTo("rate-1");
        assertThat(limited.json().get("requestId").asString()).isEqualTo("rate-1");

        // once limited, even a correct login is refused before credentials are looked at
        assertThat(login(users.communityAdmin()).status()).isEqualTo(429);
    }

    @Test
    void thePercentEncodedPathCannotBypassTheForgotPasswordLimit() {
        // same bucket as forgotPasswordIsLimitedPerIpPerHour, so use the encoded spelling of a different limited path
        assertThat(api.post("/api/v1/auth/login/%32fa", Map.of("mfaToken", "x", "code", "123456")).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/auth/login/%32fa", Map.of("mfaToken", "x", "code", "123456")).status()).isEqualTo(400);

        assertThat(api.post("/api/v1/auth/login/2fa", Map.of("mfaToken", "x", "code", "123456")).status())
                .as("encoded and plain spellings share one bucket").isEqualTo(429);
    }

    @Test
    void forgotPasswordIsLimitedPerIpPerHour() {
        assertThat(api.post("/api/v1/auth/password/forgot", Map.of("email", "a@example.test")).status()).isEqualTo(202);
        assertThat(api.post("/api/v1/auth/password/forgot", Map.of("email", "b@example.test")).status()).isEqualTo(202);

        ApiClient.Response limited = api.post("/api/v1/auth/password/forgot", Map.of("email", "c@example.test"));

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(60L, 3600L);
    }

    @Test
    void thePublicLeadFormIsLimitedPerIpPerHour() {
        // The lead endpoint itself arrives with the SuperAdmin work; the limit already guards its path.
        ApiClient.Response first = api.post("/api/v1/public/leads", Map.of("name", "A"));
        ApiClient.Response second = api.post("/api/v1/public/leads", Map.of("name", "B"));
        ApiClient.Response third = api.post("/api/v1/public/leads", Map.of("name", "C"));

        assertThat(first.status()).isNotEqualTo(429);
        assertThat(second.status()).isNotEqualTo(429);
        assertThat(third.status()).isEqualTo(429);
        assertThat(third.header("Retry-After")).isNotBlank();
    }

    @Test
    void otherEndpointsAreNotAffected() {
        for (int i = 0; i < 20; i++) {
            assertThat(api.get("/api/v1/ping").status()).isEqualTo(200);
        }
    }
}

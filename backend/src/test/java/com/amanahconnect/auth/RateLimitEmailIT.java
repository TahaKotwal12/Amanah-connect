package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The per-email login limit, separate from the per-IP limit (which is high here). */
@TestPropertySource(properties = {"app.rate-limit.login-per-email-per-minute=2"})
class RateLimitEmailIT extends AbstractAuthIT {

    @Test
    void loginAttemptsAreLimitedPerEmailWhetherOrNotTheAccountExists() {
        String known = users.communityAdmin().email();
        String unknown = "unknown-" + System.nanoTime() + "@example.test";

        for (String email : new String[] {known, unknown}) {
            assertThat(api.post("/api/v1/auth/login", Map.of("email", email, "password", "Wrong-Password-1")).status()).isEqualTo(401);
            assertThat(api.post("/api/v1/auth/login", Map.of("email", email.toUpperCase(), "password", "Wrong-Password-2")).status())
                    .as("case does not create a second bucket").isEqualTo(401);

            ApiClient.Response limited = api.post("/api/v1/auth/login", Map.of("email", email, "password", "Wrong-Password-3"));

            assertThat(limited.status()).isEqualTo(429);
            assertThat(limited.code()).isEqualTo("RATE_LIMITED");
            assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(1L, 60L);
        }
    }

    @Test
    void otherEmailsAreUnaffected() {
        String email = users.communityAdmin().email();

        assertThat(api.post("/api/v1/auth/login", Map.of("email", email, "password", "Wrong-Password-1")).status()).isEqualTo(401);
        assertThat(api.post("/api/v1/auth/login", Map.of("email", "fresh-" + System.nanoTime() + "@example.test", "password", "Wrong-Password-1")).status()).isEqualTo(401);
    }
}

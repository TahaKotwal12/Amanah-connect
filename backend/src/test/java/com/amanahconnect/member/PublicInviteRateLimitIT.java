package com.amanahconnect.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The public invite endpoints with small per-IP limits, in a context of their own. */
@TestPropertySource(properties = {"app.rate-limit.invite-view-per-ip-per-minute=3", "app.rate-limit.invite-register-per-ip-per-hour=2"})
class PublicInviteRateLimitIT extends AbstractAuthIT {

    private static final String PUBLIC = "/api/v1/public/invites/";

    @Test
    void theRegistrationFormIsLimitedPerIpPerHour() {
        String token = "R".repeat(43);
        Map<String, Object> body = Map.of("fullName", "Visitor", "email", "v@example.test");

        assertThat(api.post(PUBLIC + token + "/register", body).status()).as("processed (the link is unknown)").isEqualTo(404);
        assertThat(api.post(PUBLIC + token + "/register", body).status()).isEqualTo(404);
        ApiClient.Response limited = api.post(PUBLIC + token + "/register", body);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(60L, 3600L);
        assertThat(api.post(PUBLIC + "Q".repeat(43) + "/register", body).status()).as("another link, same IP: still limited").isEqualTo(429);
    }

    @Test
    void guessingLinksIsThrottled() {
        for (int i = 0; i < 3; i++) {
            assertThat(api.get(PUBLIC + String.valueOf((char) ('a' + i)).repeat(43)).status()).isEqualTo(404);
        }
        ApiClient.Response limited = api.get(PUBLIC + "z".repeat(43));
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.header("Retry-After")).isNotBlank();
    }

    @Test
    void otherEndpointsAreNotAffected() {
        for (int i = 0; i < 10; i++) {
            assertThat(api.get("/api/v1/ping").status()).isEqualTo(200);
        }
    }
}

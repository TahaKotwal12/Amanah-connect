package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Guessing pay links is throttled per IP. */
@TestPropertySource(properties = "app.rate-limit.pay-view-per-ip-per-minute=3")
class PublicPayRateLimitIT extends AbstractAuthIT {

    @Test
    void guessingPayLinksIsThrottled() {
        for (int i = 0; i < 3; i++) {
            assertThat(api.get("/api/v1/public/pay/" + String.valueOf((char) ('a' + i)).repeat(43)).status()).isEqualTo(404);
        }
        ApiClient.Response limited = api.get("/api/v1/public/pay/" + "z".repeat(43));
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.header("Retry-After")).isNotBlank();
    }
}

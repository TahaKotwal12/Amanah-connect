package com.amanahconnect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.TestData;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The public lead form in a context with a small per-IP limit. */
@TestPropertySource(properties = "app.rate-limit.lead-per-ip-per-hour=3")
class LeadRateLimitIT extends AbstractAuthIT {

    private Map<String, Object> form(String email) {
        return Map.of("name", "Visitor", "email", email, "message", "hello");
    }

    @Test
    void limitsSubmissionsPerIpAndStoresNothingOnceLimited() {
        String[] emails = new String[4];
        for (int i = 0; i < 4; i++) emails[i] = "rl-" + TestData.unique() + "@example.test";

        for (int i = 0; i < 3; i++) {
            assertThat(api.post("/api/v1/public/leads", form(emails[i])).status()).as("submission %d", i + 1).isEqualTo(202);
        }
        ApiClient.Response limited = api.post("/api/v1/public/leads", form(emails[3]));

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(1L, 3600L);
        assertThat(jdbc.queryForObject("select count(*) from leads where email = ?", Long.class, emails[3])).isZero();
        assertThat(jdbc.queryForObject("select count(*) from leads where email = ?", Long.class, emails[0])).isOne();
    }

    @Test
    void honeypotSubmissionsCountTowardsTheLimitToo() {
        // Runs in the same context as the test above; the bucket is shared per IP, so assert only the relative behaviour.
        Map<String, Object> bot = Map.of("name", "Bot", "email", "bot@example.test", "website", "http://spam.example");
        int limitedAt = -1;
        for (int i = 0; i < 6 && limitedAt < 0; i++) {
            if (api.post("/api/v1/public/leads", bot).status() == 429) limitedAt = i;
        }
        assertThat(limitedAt).as("bots are throttled as well").isNotEqualTo(-1);
    }
}

package com.amanahconnect.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CommunityPlanIT extends AbstractTenantIT {

    private static final String PLAN = "/api/v1/community/plan";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private JsonNode planOf(String method) {
        ApiClient.Response response = asA(method, PLAN, null);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private void paidPeriod(String status, LocalDate start, LocalDate end) {
        UUID superAdmin = users.superAdmin().id();
        jdbc.update(
                "insert into platform_subscriptions (id, community_id, plan_id, period_start, period_end, amount, reference, status, recorded_by, paid_on)"
                        + " values (?, ?, ?, ?, ?, 100, ?, ?, ?, ?)",
                UUID.randomUUID(), communityA.getId(), communityA.getPlan().getId(), start, end, "PLAN-" + UUID.randomUUID(), status, superAdmin, start);
    }

    @Test
    void showsThePlanItsLimitsAndWhatIsUsed() {
        data.member(communityA);
        data.member(communityA);
        data.member(communityB);

        JsonNode view = planOf("GET");

        assertThat(view.get("planCode").asString()).isEqualTo(communityA.getPlan().getCode());
        assertThat(view.get("limits").isObject()).isTrue();
        assertThat(view.get("features").isObject()).isTrue();
        assertThat(view.get("usage").get("members").asLong()).as("only this community's members").isEqualTo(2);
        assertThat(view.get("communityStatus").asString()).isEqualTo("ACTIVE");
        assertThat(view.get("expiringWindowDays").asInt()).isEqualTo(30);
        assertThat(view.get("subscription").isNull()).as("nothing paid yet").isTrue();
    }

    @Test
    void reportsTheLatestPaidPeriodAndHowLongIsLeft() {
        paidPeriod("ACTIVE", TODAY.minusDays(100), TODAY.plusDays(200));

        JsonNode subscription = planOf("GET").get("subscription");

        assertThat(subscription.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(subscription.get("periodEnd").asString()).isEqualTo(TODAY.plusDays(200).toString());
        assertThat(subscription.get("daysLeft").asLong()).isEqualTo(200);
    }

    @Test
    void aPeriodEndingSoonIsExpiringAndOneThatEndedIsExpired() {
        paidPeriod("ACTIVE", TODAY.minusDays(350), TODAY.plusDays(10));
        assertThat(planOf("GET").get("subscription").get("status").asString()).isEqualTo("EXPIRING");
        assertThat(planOf("GET").get("subscription").get("daysLeft").asLong()).isEqualTo(10);

        paidPeriod("ACTIVE", TODAY.plusDays(11), TODAY.plusDays(376));
        assertThat(planOf("GET").get("subscription").get("status").asString()).as("renewed").isEqualTo("ACTIVE");

        jdbc.update("delete from platform_subscriptions where community_id = ?", communityA.getId());
        paidPeriod("ACTIVE", TODAY.minusDays(400), TODAY.minusDays(5));
        JsonNode expired = planOf("GET").get("subscription");
        assertThat(expired.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(expired.get("daysLeft").asLong()).isEqualTo(-5);
    }

    @Test
    void aCancelledPaymentDoesNotCount() {
        paidPeriod("CANCELLED", TODAY.minusDays(10), TODAY.plusDays(355));

        assertThat(planOf("GET").get("subscription").isNull()).isTrue();
    }

    @Test
    void isReadableWhileSuspendedAndShowsIt() {
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());

        assertThat(planOf("GET").get("communityStatus").asString()).isEqualTo("SUSPENDED");
    }

    @Test
    void neverShowsAnotherCommunitysNumbersWhateverIsSent() {
        data.member(communityB);
        data.member(communityB);
        data.member(communityB);

        ApiClient.Response response = assertTenantSingleton("GET", PLAN, null, () -> asB("GET", PLAN, null).body());

        assertThat(response.json().get("usage").get("members").asLong()).isZero();
        assertThat(asB("GET", PLAN, null).json().get("usage").get("members").asLong()).isEqualTo(3);
    }

    @Test
    void requiresSignIn() {
        assertThat(api.get(PLAN).status()).isEqualTo(401);
    }
}

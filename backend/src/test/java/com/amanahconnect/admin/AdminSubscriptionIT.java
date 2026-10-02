package com.amanahconnect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.Community;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.AbstractAdminIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.TestData;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminSubscriptionIT extends AbstractAdminIT {

    private static final String SUBS = ADMIN + "/subscriptions";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private Map<String, Object> body(Community community, Plan plan, LocalDate start, LocalDate end, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("communityId", community.getId().toString());
        body.put("planId", plan.getId().toString());
        body.put("amount", "4990.00");
        body.put("periodStart", start.toString());
        body.put("periodEnd", end.toString());
        if (reference != null) body.put("reference", reference);
        return body;
    }

    private String statusOf(UUID id) {
        return adminGet(SUBS + "/" + id).json().get("status").asString();
    }

    @Test
    void recordsAPaymentUpdatesThePlanAndAudits() {
        Community community = data.communityOn(data.plan("STARTER"));
        Plan growth = data.plan("GROWTH");

        ApiClient.Response response = adminPost(SUBS, body(community, growth, TODAY, TODAY.plusDays(365), "UTR-" + TestData.unique()));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        var json = response.json();
        assertThat(json.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(json.get("amount").asString()).isEqualTo("4990.00");
        assertThat(json.get("planCode").asString()).isEqualTo("GROWTH");
        assertThat(json.get("paidOn").asString()).isEqualTo(TODAY.toString());
        assertThat(json.get("daysLeft").asInt()).isEqualTo(365);
        assertThat(jdbc.queryForObject("select plan_id from communities where id = ?", UUID.class, community.getId())).isEqualTo(growth.getId());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'SUBSCRIPTION_RECORDED' and community_id = ?", Long.class, community.getId())).isOne();
    }

    @Test
    void validatesInput() {
        Community community = data.communityOn(data.plan("STARTER"));
        Plan plan = data.plan("STARTER");

        assertThat(adminPost(SUBS, body(community, plan, TODAY, TODAY, null)).status()).as("end must be after start").isEqualTo(400);
        assertThat(adminPost(SUBS, body(community, plan, TODAY, TODAY.minusDays(1), null)).status()).isEqualTo(400);

        Map<String, Object> future = body(community, plan, TODAY, TODAY.plusDays(30), null);
        future.put("paidOn", TODAY.plusDays(2).toString());
        assertThat(adminPost(SUBS, future).status()).isEqualTo(400);

        Map<String, Object> badAmount = body(community, plan, TODAY, TODAY.plusDays(30), null);
        badAmount.put("amount", "10.999");
        assertThat(adminPost(SUBS, badAmount).status()).isEqualTo(400);

        Map<String, Object> unknownCommunity = body(community, plan, TODAY, TODAY.plusDays(30), null);
        unknownCommunity.put("communityId", UUID.randomUUID().toString());
        assertThat(adminPost(SUBS, unknownCommunity).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from platform_subscriptions where community_id = ?", Long.class, community.getId())).isZero();
    }

    @Test
    void refusesADuplicateReferenceButAllowsItAgainAfterCancellation() {
        Community community = data.communityOn(data.plan("STARTER"));
        Community other = data.communityOn(data.plan("STARTER"));
        Plan plan = data.plan("STARTER");
        String reference = "UTR-" + TestData.unique();

        ApiClient.Response first = adminPost(SUBS, body(community, plan, TODAY, TODAY.plusDays(30), reference));
        assertThat(first.status()).isEqualTo(201);

        ApiClient.Response again = adminPost(SUBS, body(community, plan, TODAY.plusDays(30), TODAY.plusDays(60), reference.toLowerCase()));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("DUPLICATE_REFERENCE");

        assertThat(adminPost(SUBS, body(other, plan, TODAY, TODAY.plusDays(30), reference)).status()).as("another community").isEqualTo(201);

        UUID id = UUID.fromString(first.json().get("id").asString());
        assertThat(adminPost(SUBS + "/" + id + "/cancel", Map.of("reason", "bounced cheque")).status()).isEqualTo(200);
        assertThat(adminPost(SUBS, body(community, plan, TODAY, TODAY.plusDays(30), reference)).status()).isEqualTo(201);
    }

    @Test
    void derivesActiveExpiringAndExpiredStatuses() {
        Plan plan = data.plan("STARTER");
        Community active = data.communityOn(plan);
        Community expiring = data.communityOn(plan);
        Community expired = data.communityOn(plan);
        Community renewed = data.communityOn(plan);

        UUID activeId = insertSubscription(active, plan, TODAY.minusDays(10), TODAY.plusDays(200), "ACTIVE", "A-" + TestData.unique(), "100.00");
        UUID expiringId = insertSubscription(expiring, plan, TODAY.minusDays(300), TODAY.plusDays(10), "ACTIVE", "E-" + TestData.unique(), "100.00");
        UUID expiredId = insertSubscription(expired, plan, TODAY.minusDays(400), TODAY.minusDays(1), "ACTIVE", "X-" + TestData.unique(), "100.00");
        UUID storedExpiredId = insertSubscription(expired, plan, TODAY.minusDays(800), TODAY.minusDays(430), "EXPIRED", "Y-" + TestData.unique(), "100.00");
        UUID oldId = insertSubscription(renewed, plan, TODAY.minusDays(360), TODAY.plusDays(5), "ACTIVE", "R1-" + TestData.unique(), "100.00");
        insertSubscription(renewed, plan, TODAY.plusDays(5), TODAY.plusDays(370), "ACTIVE", "R2-" + TestData.unique(), "100.00");
        UUID cancelledId = insertSubscription(active, plan, TODAY.minusDays(1), TODAY.plusDays(5), "CANCELLED", "C-" + TestData.unique(), "100.00");

        assertThat(statusOf(activeId)).isEqualTo("ACTIVE");
        assertThat(statusOf(expiringId)).isEqualTo("EXPIRING");
        assertThat(statusOf(expiredId)).as("past period_end is expired even before the job runs").isEqualTo("EXPIRED");
        assertThat(statusOf(storedExpiredId)).isEqualTo("EXPIRED");
        assertThat(statusOf(oldId)).as("renewed by a later subscription, so not expiring").isEqualTo("ACTIVE");
        assertThat(statusOf(cancelledId)).isEqualTo("CANCELLED");
    }

    @Test
    void filtersByStatusAndCommunity() {
        Plan plan = data.plan("STARTER");
        Community c1 = data.communityOn(plan);
        Community c2 = data.communityOn(plan);
        Community c3 = data.communityOn(plan);
        UUID activeId = insertSubscription(c1, plan, TODAY.minusDays(10), TODAY.plusDays(200), "ACTIVE", "A-" + TestData.unique(), "100.00");
        UUID expiringId = insertSubscription(c2, plan, TODAY.minusDays(300), TODAY.plusDays(10), "ACTIVE", "E-" + TestData.unique(), "100.00");
        UUID expiredId = insertSubscription(c3, plan, TODAY.minusDays(400), TODAY.minusDays(1), "ACTIVE", "X-" + TestData.unique(), "100.00");

        assertThat(contentIds(adminGet(SUBS + "?communityId=" + c1.getId()))).containsExactly(activeId.toString());
        assertThat(contentIds(adminGet(SUBS + "?status=ACTIVE&communityId=" + c1.getId()))).containsExactly(activeId.toString());
        assertThat(contentIds(adminGet(SUBS + "?status=EXPIRING&communityId=" + c1.getId()))).isEmpty();
        assertThat(contentIds(adminGet(SUBS + "?status=EXPIRING&communityId=" + c2.getId()))).containsExactly(expiringId.toString());
        assertThat(contentIds(adminGet(SUBS + "?status=EXPIRED&communityId=" + c3.getId()))).containsExactly(expiredId.toString());
        assertThat(contentIds(adminGet(SUBS + "?status=ACTIVE&communityId=" + c3.getId()))).isEmpty();
        assertThat(adminGet(SUBS + "?status=BOGUS").status()).isEqualTo(400);
        assertThat(adminGet(SUBS + "?sort=amount;drop,asc").status()).isEqualTo(400);

        // Renewal: a later ACTIVE subscription on the same community stops the earlier one counting as expiring.
        insertSubscription(c2, plan, TODAY.plusDays(10), TODAY.plusDays(375), "ACTIVE", "E2-" + TestData.unique(), "100.00");
        assertThat(contentIds(adminGet(SUBS + "?status=EXPIRING&communityId=" + c2.getId()))).isEmpty();
    }

    private java.util.List<String> contentIds(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        response.json().get("items").forEach(n -> ids.add(n.get("id").asString()));
        return ids;
    }

    @Test
    void expiringEndpointListsSubscriptionsEndingWithinNDays() {
        Plan plan = data.plan("STARTER");
        Community soon = data.communityOn(plan);
        Community later = data.communityOn(plan);
        Community expired = data.communityOn(plan);
        UUID soonId = insertSubscription(soon, plan, TODAY.minusDays(350), TODAY.plusDays(6), "ACTIVE", "S-" + TestData.unique(), "100.00");
        UUID laterId = insertSubscription(later, plan, TODAY.minusDays(300), TODAY.plusDays(20), "ACTIVE", "L-" + TestData.unique(), "100.00");
        UUID expiredId = insertSubscription(expired, plan, TODAY.minusDays(400), TODAY.minusDays(2), "ACTIVE", "X-" + TestData.unique(), "100.00");

        assertThat(contentIds(adminGet(SUBS + "/expiring?days=7"))).contains(soonId.toString()).doesNotContain(laterId.toString(), expiredId.toString());
        assertThat(contentIds(adminGet(SUBS + "/expiring?days=30"))).contains(soonId.toString(), laterId.toString()).doesNotContain(expiredId.toString());
        assertThat(adminGet(SUBS + "/expiring?days=0").status()).isEqualTo(400);
        assertThat(adminGet(SUBS + "/expiring?days=366").status()).isEqualTo(400);
        assertThat(adminGet(SUBS + "/expiring").status()).as("default window").isEqualTo(200);
    }

    @Test
    void aBackDatedRecordIsStoredExpiredAndDoesNotChangeTheCurrentPlan() {
        Plan starter = data.plan("STARTER");
        Plan growth = data.plan("GROWTH");
        Community community = data.communityOn(growth);
        insertSubscription(community, growth, TODAY.minusDays(10), TODAY.plusDays(300), "ACTIVE", "CUR-" + TestData.unique(), "100.00");

        ApiClient.Response response = adminPost(SUBS, body(community, starter, TODAY.minusDays(400), TODAY.minusDays(35), "OLD-" + TestData.unique()));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.json().get("status").asString()).isEqualTo("EXPIRED");
        UUID id = UUID.fromString(response.json().get("id").asString());
        Map<String, Object> row = jdbc.queryForMap("select status, expired_notified_at from platform_subscriptions where id = ?", id);
        assertThat(row.get("status")).isEqualTo("EXPIRED");
        assertThat(row.get("expired_notified_at")).as("history entry must not trigger an expiry email").isNotNull();
        assertThat(jdbc.queryForObject("select plan_id from communities where id = ?", UUID.class, community.getId())).isEqualTo(growth.getId());
    }

    @Test
    void cancelsOnceWithAReasonAndKeepsTheRow() {
        Community community = data.communityOn(data.plan("STARTER"));
        ApiClient.Response created = adminPost(SUBS, body(community, data.plan("STARTER"), TODAY, TODAY.plusDays(30), null));
        UUID id = UUID.fromString(created.json().get("id").asString());

        assertThat(adminPost(SUBS + "/" + id + "/cancel", Map.of("reason", " ")).status()).isEqualTo(400);
        ApiClient.Response cancelled = adminPost(SUBS + "/" + id + "/cancel", Map.of("reason", "Refunded"));
        assertThat(cancelled.status()).as(cancelled.body()).isEqualTo(200);
        assertThat(cancelled.json().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.json().get("cancelReason").asString()).isEqualTo("Refunded");

        ApiClient.Response again = adminPost(SUBS + "/" + id + "/cancel", Map.of("reason", "Again"));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("INVALID_STATE_TRANSITION");

        assertThat(jdbc.queryForObject("select count(*) from platform_subscriptions where id = ?", Long.class, id)).as("never hard-deleted").isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'SUBSCRIPTION_CANCELLED' and entity_id = ?", Long.class, id)).isOne();
        assertThat(adminGet(SUBS + "/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(adminPost(SUBS + "/" + UUID.randomUUID() + "/cancel", Map.of("reason", "x")).status()).isEqualTo(404);
    }
}

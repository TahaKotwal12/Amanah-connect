package com.amanahconnect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.Community;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.AbstractAdminIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.TestData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminPlanIT extends AbstractAdminIT {

    private static final String PLANS = ADMIN + "/plans";

    private Map<String, Object> planBody(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Plan " + code);
        body.put("priceMonthly", "499.00");
        body.put("priceYearly", "4990.00");
        body.put("limits", Map.of("max_members", 250, "emails_per_month", 1000));
        body.put("features", Map.of("exports", true, "bulk_email", false));
        return body;
    }

    private UUID createPlan(String code) {
        ApiClient.Response response = adminPost(PLANS, planBody(code));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    @Test
    void createsAPlanWithMoneyAsStringsAndAudits() {
        String code = "P_" + TestData.unique().toUpperCase();
        ApiClient.Response response = adminPost(PLANS, planBody(code));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        var json = response.json();
        assertThat(json.get("code").asString()).isEqualTo(code);
        assertThat(json.get("priceMonthly").asString()).isEqualTo("499.00");
        assertThat(json.get("priceMonthly").asString()).isEqualTo("499.00");
        assertThat(json.get("active").asBoolean()).isTrue();
        assertThat(json.get("publicPlan").asBoolean()).isTrue();
        assertThat(json.get("limits").get("max_members").asInt()).isEqualTo(250);
        assertThat(json.get("communityCount").asInt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'PLAN_CREATED' and entity_id = ?", Long.class,
                UUID.fromString(json.get("id").asString()))).isOne();
    }

    @Test
    void refusesADuplicateCodeAndInvalidDefinitions() {
        String code = "P_" + TestData.unique().toUpperCase();
        createPlan(code);

        ApiClient.Response duplicate = adminPost(PLANS, planBody(code));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("CODE_TAKEN");

        Map<String, Object> lower = planBody("lower_case");
        assertThat(adminPost(PLANS, lower).status()).isEqualTo(400);

        Map<String, Object> badLimit = planBody("P_" + TestData.unique().toUpperCase());
        badLimit.put("limits", Map.of("max_members", -1));
        assertThat(adminPost(PLANS, badLimit).status()).isEqualTo(400);

        Map<String, Object> unknownLimit = planBody("P_" + TestData.unique().toUpperCase());
        unknownLimit.put("limits", Map.of("whatever", 5));
        assertThat(adminPost(PLANS, unknownLimit).status()).isEqualTo(400);

        Map<String, Object> badFeature = planBody("P_" + TestData.unique().toUpperCase());
        badFeature.put("features", Map.of("exports", "yes"));
        assertThat(adminPost(PLANS, badFeature).status()).isEqualTo(400);

        Map<String, Object> badPrice = planBody("P_" + TestData.unique().toUpperCase());
        badPrice.put("priceMonthly", "12.345");
        assertThat(adminPost(PLANS, badPrice).status()).isEqualTo(400);

        Map<String, Object> negative = planBody("P_" + TestData.unique().toUpperCase());
        negative.put("priceYearly", "-1.00");
        assertThat(adminPost(PLANS, negative).status()).isEqualTo(400);
    }

    @Test
    void listsAllPlansIncludingInactiveAndFiltersThem() {
        UUID id = createPlan("P_" + TestData.unique().toUpperCase());
        assertThat(adminPost(PLANS + "/" + id + "/deactivate", null).status()).isEqualTo(200);

        List<String> all = ids(adminGet(PLANS));
        assertThat(all).contains(id.toString());
        assertThat(ids(adminGet(PLANS + "?active=true"))).doesNotContain(id.toString());
        assertThat(ids(adminGet(PLANS + "?active=false"))).contains(id.toString());
        assertThat(ids(adminGet(PLANS))).as("seeded plans are present").hasSizeGreaterThanOrEqualTo(3);
    }

    private List<String> ids(ApiClient.Response response) {
        assertThat(response.status()).isEqualTo(200);
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        response.json().forEach(node -> ids.add(node.get("id").asString()));
        return ids;
    }

    @Test
    void updatesPartiallyAndNeverChangesTheCode() {
        String code = "P_" + TestData.unique().toUpperCase();
        UUID id = createPlan(code);

        ApiClient.Response response = admin("PATCH", PLANS + "/" + id, Map.of("name", "Renamed", "priceMonthly", "599.50", "code", "HIJACK"));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().get("name").asString()).isEqualTo("Renamed");
        assertThat(response.json().get("priceMonthly").asString()).isEqualTo("599.50");
        assertThat(response.json().get("priceYearly").asString()).as("untouched").isEqualTo("4990.00");
        assertThat(response.json().get("code").asString()).isEqualTo(code);
        Map<String, Object> audit = jdbc.queryForMap("select * from audit_logs where action = 'PLAN_UPDATED' and entity_id = ?", id);
        assertThat(audit.get("before").toString()).contains("499");
        assertThat(audit.get("after").toString()).contains("599.5");
    }

    @Test
    void deactivatingAPlanLeavesCommunitiesOnItUntouched() {
        Plan plan = data.customPlan("Legacy", Map.of("max_members", 5), Map.of());
        Community community = data.communityOn(plan);
        Community other = data.communityOn(plan);

        assertThat(adminPost(PLANS + "/" + plan.getId() + "/deactivate", null).status()).isEqualTo(200);

        assertThat(jdbc.queryForObject("select plan_id from communities where id = ?", UUID.class, community.getId())).isEqualTo(plan.getId());
        assertThat(jdbc.queryForObject("select status from communities where id = ?", String.class, community.getId())).isEqualTo("ACTIVE");
        ApiClient.Response view = adminGet(PLANS + "/" + plan.getId());
        assertThat(view.json().get("active").asBoolean()).isFalse();
        assertThat(view.json().get("communityCount").asInt()).isEqualTo(2);
        assertThat(other.getId()).isNotNull();

        // The community admin keeps working: the inactive plan's limits still apply.
        assertThat(adminGet(ADMIN + "/communities/" + community.getId()).status()).isEqualTo(200);

        // But no new community can pick it, and no new subscription can be recorded on it.
        ApiClient.Response create = adminPost(ADMIN + "/communities", newCommunityBody(plan, "Late Joiner", "late-" + TestData.unique() + "@example.test"));
        assertThat(create.status()).isGreaterThanOrEqualTo(400);

        Map<String, Object> subscription = new LinkedHashMap<>();
        subscription.put("communityId", community.getId().toString());
        subscription.put("planId", plan.getId().toString());
        subscription.put("amount", "100.00");
        subscription.put("periodStart", java.time.LocalDate.now().toString());
        subscription.put("periodEnd", java.time.LocalDate.now().plusDays(30).toString());
        ApiClient.Response record = adminPost(ADMIN + "/subscriptions", subscription);
        assertThat(record.status()).isEqualTo(409);
        assertThat(record.code()).isEqualTo("PLAN_INACTIVE");

        assertThat(adminPost(PLANS + "/" + plan.getId() + "/activate", null).status()).isEqualTo(200);
        assertThat(adminPost(ADMIN + "/subscriptions", subscription).status()).isEqualTo(201);
    }

    @Test
    void unknownPlanIs404() {
        assertThat(adminGet(PLANS + "/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(admin("PATCH", PLANS + "/" + UUID.randomUUID(), Map.of("name", "x")).status()).isEqualTo(404);
        assertThat(adminPost(PLANS + "/" + UUID.randomUUID() + "/deactivate", null).status()).isEqualTo(404);
    }
}

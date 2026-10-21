package com.amanahconnect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAdminIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.TestData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PublicPlanIT extends AbstractAdminIT {

    private static final String PUBLIC_PLANS = "/api/v1/public/plans";

    private String code() {
        return "PUB_" + TestData.unique().toUpperCase();
    }

    private UUID createPlan(String code, boolean publicPlan, int sortOrder) {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("max_members", null); // null means unlimited and must survive the round trip
        limits.put("emails_per_month", 750);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Public plan " + code);
        body.put("priceMonthly", "499.00");
        body.put("priceYearly", "4990.00");
        body.put("limits", limits);
        body.put("features", Map.of("upi_qr", true, "pdf_reports", false));
        body.put("publicPlan", publicPlan);
        body.put("sortOrder", sortOrder);
        ApiClient.Response response = adminPost(ADMIN + "/plans", body);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    private JsonNode find(ApiClient.Response response, String code) {
        for (JsonNode plan : response.json()) {
            if (code.equals(plan.get("code").asString())) {
                return plan;
            }
        }
        return null;
    }

    private List<String> codes(ApiClient.Response response) {
        List<String> codes = new ArrayList<>();
        response.json().forEach(plan -> codes.add(plan.get("code").asString()));
        return codes;
    }

    @Test
    void anyoneCanListThePlansWithoutSigningIn() {
        String code = code();
        createPlan(code, true, 90_000);

        ApiClient.Response response = api.get(PUBLIC_PLANS);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().isArray()).isTrue();
        assertThat(find(response, code)).isNotNull();
    }

    @Test
    void aStaleOrGarbageTokenDoesNotBreakAPublicPage() {
        ApiClient.Response response = api.get(PUBLIC_PLANS, "Authorization", "Bearer not-a-real-token");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    @Test
    void showsOnlyWhatTheWebsiteNeeds() {
        String code = code();
        createPlan(code, true, 90_001);

        JsonNode plan = find(api.get(PUBLIC_PLANS), code);

        assertThat(plan).isNotNull();
        assertThat(plan.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("code", "name", "priceMonthly", "priceYearly", "limits", "features");
        assertThat(plan.get("priceMonthly").asString()).isEqualTo("499.00");
        assertThat(plan.get("priceMonthly").isString()).as("money is a string, never a float").isTrue();
        assertThat(plan.get("priceYearly").asString()).isEqualTo("4990.00");
        assertThat(plan.get("limits").get("max_members").isNull()).as("null limit means unlimited").isTrue();
        assertThat(plan.get("limits").get("emails_per_month").asInt()).isEqualTo(750);
        assertThat(plan.get("features").get("upi_qr").asBoolean()).isTrue();
        assertThat(plan.get("features").get("pdf_reports").asBoolean()).isFalse();
    }

    @Test
    void leavesOutPlansThatAreNotPublicOrNotActive() {
        String hidden = code();
        String retired = code();
        String shown = code();
        createPlan(hidden, false, 90_010);
        UUID retiredId = createPlan(retired, true, 90_011);
        createPlan(shown, true, 90_012);
        assertThat(adminPost(ADMIN + "/plans/" + retiredId + "/deactivate", Map.of()).status()).isEqualTo(200);

        ApiClient.Response response = api.get(PUBLIC_PLANS);

        assertThat(codes(response)).contains(shown).doesNotContain(hidden, retired);
    }

    @Test
    void listsPlansInDisplayOrder() {
        String later = code();
        String earlier = code();
        createPlan(later, true, 90_021);
        createPlan(earlier, true, 90_020);

        List<String> codes = codes(api.get(PUBLIC_PLANS));

        assertThat(codes.indexOf(earlier)).isNotNegative().isLessThan(codes.indexOf(later));
    }

    @Test
    void mayBeCachedBrieflyByTheBrowser() {
        ApiClient.Response response = api.get(PUBLIC_PLANS);

        String cacheControl = response.headers().firstValue("Cache-Control").orElse("");
        assertThat(cacheControl).contains("max-age=60").contains("public");
    }
}

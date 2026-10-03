package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class FeePlanIT extends AbstractFinanceIT {

    private Map<String, Object> body(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("kind", "MAINTENANCE");
        body.put("amount", "1500.00");
        body.put("frequency", "MONTHLY");
        return body;
    }

    @Test
    void createsAPlanWithMoneyAsAStringAndAudits() {
        Map<String, Object> request = body("Monthly maintenance");
        request.put("dueDay", 5);

        ApiClient.Response response = asA("POST", PLANS, request);

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode plan = response.json();
        assertThat(plan.get("amount").asString()).isEqualTo("1500.00");
        assertThat(plan.get("kind").asString()).isEqualTo("MAINTENANCE");
        assertThat(plan.get("frequency").asString()).isEqualTo("MONTHLY");
        assertThat(plan.get("dueDay").asInt()).isEqualTo(5);
        assertThat(plan.get("appliesTo").asString()).isEqualTo("ALL_ACTIVE");
        assertThat(plan.get("active").asBoolean()).isTrue();
        assertThat(plan.get("autoGenerate").asBoolean()).as("nobody is billed automatically unless asked").isFalse();
        assertThat(plan.get("currentPeriod").asString()).isEqualTo("%d-%02d".formatted(TODAY.getYear(), TODAY.getMonthValue()));
        assertThat(count("select count(*) from audit_logs where action = 'FEE_PLAN_CREATED' and entity_id = ?", id(plan))).isOne();
    }

    @Test
    void acceptsEveryKindExceptTheLegacyOne() {
        for (String kind : List.of("MAINTENANCE", "SUBSCRIPTION", "DONATION", "EVENT", "FINE", "OTHER")) {
            Map<String, Object> request = body("Plan " + kind);
            request.put("kind", kind);
            assertThat(asA("POST", PLANS, request).status()).as(kind).isEqualTo(201);
        }
        Map<String, Object> legacy = body("Legacy");
        legacy.put("kind", "MEMBERSHIP");
        ApiClient.Response response = asA("POST", PLANS, legacy);
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().toString()).contains("imported invoices");
        Map<String, Object> unknown = body("Unknown");
        unknown.put("kind", "TAX");
        assertThat(asA("POST", PLANS, unknown).status()).isEqualTo(400);
    }

    @Test
    void validatesAmountFrequencyAndDueDay() {
        for (String bad : new String[] {"0", "-5.00", "10.999", "abc", "1000000000000.00"}) {
            Map<String, Object> request = body("Bad amount");
            request.put("amount", bad);
            assertThat(asA("POST", PLANS, request).status()).as(bad).isEqualTo(400);
        }
        Map<String, Object> request = body("Bad day");
        request.put("dueDay", 29);
        assertThat(asA("POST", PLANS, request).status()).as("every month has a 28th").isEqualTo(400);
        request.put("dueDay", 0);
        assertThat(asA("POST", PLANS, request).status()).isEqualTo(400);
        request = body("Bad frequency");
        request.put("frequency", "WEEKLY");
        assertThat(asA("POST", PLANS, request).status()).isEqualTo(400);
        assertThat(asA("POST", PLANS, body(" ")).status()).isEqualTo(400);
        Map<String, Object> noAmount = body("No amount");
        noAmount.remove("amount");
        assertThat(asA("POST", PLANS, noAmount).status()).isEqualTo(400);
        Map<String, Object> small = body("Small");
        small.put("amount", "0.01");
        assertThat(asA("POST", PLANS, small).status()).isEqualTo(201);
    }

    @Test
    void audiencesAreValidated() {
        Map<String, Object> group = body("Group plan");
        group.put("appliesTo", "GROUP");
        assertThat(asA("POST", PLANS, group).status()).as("a group is required").isEqualTo(400);
        group.put("group", "Block A");
        ApiClient.Response ok = asA("POST", PLANS, group);
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.json().get("group").asString()).isEqualTo("Block A");

        Map<String, Object> selected = body("Selected");
        selected.put("appliesTo", "SELECTED");
        assertThat(asA("POST", PLANS, selected).status()).as("members are required").isEqualTo(400);
        UUID mine = memberA("Mine");
        UUID theirs = data.member(communityB).getId();
        selected.put("memberIds", List.of(mine.toString(), theirs.toString()));
        ApiClient.Response foreign = asA("POST", PLANS, selected);
        assertThat(foreign.status()).as("another community's member looks like an unknown member").isEqualTo(400);
        assertThat(foreign.json().toString()).contains("unknown member");
        selected.put("memberIds", List.of(mine.toString(), UUID.randomUUID().toString()));
        assertThat(asA("POST", PLANS, selected).status()).isEqualTo(400);
        selected.put("memberIds", List.of(mine.toString(), mine.toString()));
        ApiClient.Response dedup = asA("POST", PLANS, selected);
        assertThat(dedup.status()).isEqualTo(201);
        assertThat(dedup.json().get("memberIds").size()).as("duplicates collapse").isOne();
    }

    @Test
    void automaticBillingStartsWithTheNextPeriod() {
        Map<String, Object> request = body("Auto");
        request.put("autoGenerate", true);

        JsonNode plan = asA("POST", PLANS, request).json();

        assertThat(plan.get("autoGenerate").asBoolean()).isTrue();
        assertThat(plan.get("lastGeneratedPeriod").asString()).as("this period is not billed by surprise").isEqualTo(plan.get("currentPeriod").asString());
        Map<String, Object> oneTime = body("One time auto");
        oneTime.put("frequency", "ONE_TIME");
        oneTime.put("autoGenerate", true);
        assertThat(asA("POST", PLANS, oneTime).status()).isEqualTo(400);

        JsonNode manual = asA("POST", PLANS, body("Manual")).json();
        assertThat(manual.get("lastGeneratedPeriod").isNull()).isTrue();
        ApiClient.Response enabled = asA("PATCH", PLANS + "/" + id(manual), Map.of("autoGenerate", true));
        assertThat(enabled.json().get("lastGeneratedPeriod").asString()).as("switching it on also starts from the next period").isEqualTo(manual.get("currentPeriod").asString());
    }

    @Test
    void updatingChangesFuturePricesButNeverIssuedInvoices() {
        JsonNode plan = asA("POST", PLANS, body("Price change")).json();
        UUID member = memberA("Payer");
        generate(sessionA, id(plan), null);
        UUID invoiceId = UUID.fromString(jdbc.queryForObject("select id::text from invoices where fee_plan_id = ?", String.class, id(plan)));

        ApiClient.Response updated = asA("PATCH", PLANS + "/" + id(plan), Map.of("amount", "2000.50", "name", "Renamed plan", "dueDay", 12));

        assertThat(updated.status()).as(updated.body()).isEqualTo(200);
        assertThat(updated.json().get("amount").asString()).isEqualTo("2000.50");
        assertThat(updated.json().get("kind").asString()).as("kind and frequency are fixed").isEqualTo("MAINTENANCE");
        JsonNode invoice = invoiceView(sessionA, invoiceId).get("invoice");
        assertThat(invoice.get("amount").asString()).as("the issued invoice keeps its price").isEqualTo("1500.00");
        assertThat(invoice.get("description").asString()).as("and the plan's old name").isEqualTo("Price change");
        assertThat(member).isNotNull();
        Map<String, Object> before = jdbc.queryForMap("select before::text as b, after::text as a from audit_logs where action = 'FEE_PLAN_UPDATED' and entity_id = ?", id(plan));
        assertThat(before.get("b").toString()).contains("1500.00");
        assertThat(before.get("a").toString()).contains("2000.50");
        assertThat(asA("PATCH", PLANS + "/" + id(plan), Map.of("amount", "0")).status()).isEqualTo(400);
        assertThat(asA("PATCH", PLANS + "/" + id(plan), Map.of("name", "")).status()).isEqualTo(400);
    }

    @Test
    void plansAreSwitchedOffNotDeletedAndListFilters() {
        JsonNode active = asA("POST", PLANS, body("Active one")).json();
        JsonNode retired = asA("POST", PLANS, body("Retired one")).json();
        asA("PATCH", PLANS + "/" + id(retired), Map.of("active", false));

        assertThat(ids(asA("GET", PLANS + "?active=true", null))).contains(id(active).toString()).doesNotContain(id(retired).toString());
        assertThat(ids(asA("GET", PLANS + "?active=false", null))).contains(id(retired).toString()).doesNotContain(id(active).toString());
        assertThat(ids(asA("GET", PLANS, null))).contains(id(active).toString(), id(retired).toString());
        assertThat(count("select count(*) from fee_plans where id = ?", id(retired))).isOne();
        assertThat(asA("DELETE", PLANS + "/" + id(retired), null).status()).as("there is no delete").isIn(404, 405);
    }

    private List<String> ids(ApiClient.Response response) {
        assertThat(response.status()).isEqualTo(200);
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        response.json().forEach(n -> ids.add(n.get("id").asString()));
        return ids;
    }
}

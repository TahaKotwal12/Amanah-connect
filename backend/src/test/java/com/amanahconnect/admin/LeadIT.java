package com.amanahconnect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAdminIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.TestData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LeadIT extends AbstractAdminIT {

    private static final String PUBLIC = "/api/v1/public/leads";
    private static final String LEADS = ADMIN + "/leads";

    private Map<String, Object> form(String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Priya Sharma");
        body.put("email", email);
        body.put("phone", "+91 98765 43210");
        body.put("communityName", "Lotus Residents " + TestData.unique());
        body.put("size", 120);
        body.put("message", "We would like a demo.\nThanks!");
        return body;
    }

    private String leadEmail() {
        return "lead-" + TestData.unique() + "@example.test";
    }

    private long leadCount(String email) {
        return jdbc.queryForObject("select count(*) from leads where email = ?", Long.class, email);
    }

    private UUID leadId(String email) {
        return jdbc.queryForObject("select id from leads where email = ?", UUID.class, email);
    }

    // ---- public form -------------------------------------------------------------------------------

    @Test
    void storesTheLeadEmailsTheSuperAdminAndAcknowledgesTheVisitor() {
        String email = leadEmail();

        ApiClient.Response response = api.post(PUBLIC, form(email));

        assertThat(response.status()).as(response.body()).isEqualTo(202);
        assertThat(response.json().get("received").asBoolean()).isTrue();
        Map<String, Object> row = jdbc.queryForMap("select * from leads where email = ?", email);
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("name")).isEqualTo("Priya Sharma");
        assertThat(row.get("size_estimate")).isEqualTo(120);
        assertThat(row.get("message")).isEqualTo("We would like a demo.\nThanks!");
        assertThat(row.get("source")).isEqualTo("WEBSITE");

        List<Map<String, Object>> notices = emailsTo(superAdmin.email(), "lead-notification");
        assertThat(notices).as("super admin is told").isNotEmpty();
        assertThat(notices.stream().map(n -> n.get("payload").toString())).anyMatch(p -> p.contains(email));
        assertThat(emailsTo(email, "lead-acknowledgement")).as("the visitor gets an acknowledgement").hasSize(1);

        List<Map<String, Object>> audit = jdbc.queryForList("select * from audit_logs where action = 'LEAD_RECEIVED' and entity_id = ?", row.get("id"));
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("actor_user_id")).isNull();
        assertThat(audit.get(0).toString()).as("no PII in the audit log").doesNotContain(email).doesNotContain("Priya");
    }

    @Test
    void aFilledHoneypotIsAcceptedSilentlyButNothingIsStoredOrSent() {
        String email = leadEmail();
        Map<String, Object> body = form(email);
        body.put("website", "http://spam.example");

        ApiClient.Response response = api.post(PUBLIC, body);

        assertThat(response.status()).as("a bot must not learn it was caught").isEqualTo(202);
        assertThat(response.json().get("received").asBoolean()).isTrue();
        assertThat(leadCount(email)).isZero();
        assertThat(emailsTo(email, "lead-acknowledgement")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where template = 'lead-notification' and payload::text like ?", Long.class, "%" + email + "%")).isZero();

        Map<String, Object> blank = form(leadEmail());
        blank.put("website", "   ");
        assertThat(api.post(PUBLIC, blank).status()).isEqualTo(202);
        assertThat(leadCount((String) blank.get("email"))).as("a blank honeypot is a real visitor").isOne();
    }

    @Test
    void enforcesLengthLimitsAndFormats() {
        Map<String, Object> longName = form(leadEmail());
        longName.put("name", "x".repeat(151));
        assertThat(api.post(PUBLIC, longName).status()).isEqualTo(400);

        Map<String, Object> longMessage = form(leadEmail());
        longMessage.put("message", "x".repeat(2001));
        assertThat(api.post(PUBLIC, longMessage).status()).isEqualTo(400);

        Map<String, Object> longCommunity = form(leadEmail());
        longCommunity.put("communityName", "x".repeat(201));
        assertThat(api.post(PUBLIC, longCommunity).status()).isEqualTo(400);

        Map<String, Object> longEmail = form("a".repeat(250) + "@example.test");
        assertThat(api.post(PUBLIC, longEmail).status()).isEqualTo(400);

        Map<String, Object> badEmail = form("not-an-email");
        assertThat(api.post(PUBLIC, badEmail).status()).isEqualTo(400);

        Map<String, Object> badPhone = form(leadEmail());
        badPhone.put("phone", "call me <script>");
        assertThat(api.post(PUBLIC, badPhone).status()).isEqualTo(400);

        Map<String, Object> zero = form(leadEmail());
        zero.put("size", 0);
        assertThat(api.post(PUBLIC, zero).status()).isEqualTo(400);

        Map<String, Object> huge = form(leadEmail());
        huge.put("size", 10_000_001);
        assertThat(api.post(PUBLIC, huge).status()).isEqualTo(400);

        Map<String, Object> noName = form(leadEmail());
        noName.remove("name");
        assertThat(api.post(PUBLIC, noName).status()).isEqualTo(400);

        Map<String, Object> atLimit = form(leadEmail());
        atLimit.put("name", "n".repeat(150));
        atLimit.put("message", "m".repeat(2000));
        assertThat(api.post(PUBLIC, atLimit).status()).as("the limits themselves are allowed").isEqualTo(202);
    }

    @Test
    void stripsControlCharactersAndCollapsesWhitespace() {
        String email = leadEmail();
        Map<String, Object> body = form(email);
        body.put("name", "  Priya\u0000\u0007 \n Sharma  ");
        body.put("message", "line one\u0000\nline two\u0008");

        assertThat(api.post(PUBLIC, body).status()).isEqualTo(202);

        Map<String, Object> row = jdbc.queryForMap("select name, message from leads where email = ?", email);
        assertThat(row.get("name")).isEqualTo("Priya Sharma");
        assertThat(row.get("message")).isEqualTo("line one\nline two");

        Map<String, Object> onlyControl = form(leadEmail());
        onlyControl.put("name", "\u0000\u0001 ");
        assertThat(api.post(PUBLIC, onlyControl).status()).as("a name that is only control characters is empty").isEqualTo(400);
    }

    @Test
    void acknowledgesTheSameVisitorOnlyOncePerHour() {
        String email = leadEmail();

        assertThat(api.post(PUBLIC, form(email)).status()).isEqualTo(202);
        assertThat(api.post(PUBLIC, form(email)).status()).isEqualTo(202);

        assertThat(leadCount(email)).as("both submissions are kept").isEqualTo(2);
        assertThat(emailsTo(email, "lead-acknowledgement")).hasSize(1);
    }

    @Test
    void rejectsOversizedBodiesBeforeReadingThem() {
        Map<String, Object> body = form(leadEmail());
        body.put("website", "x".repeat(20_000));

        ApiClient.Response response = api.post(PUBLIC, body);

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
    }

    @Test
    void ignoresAStaleAuthorizationHeaderOnThePublicEndpoint() {
        ApiClient.Response response = api.post(PUBLIC, form(leadEmail()), "Authorization", "Bearer not.a.token");
        assertThat(response.status()).isEqualTo(202);
    }

    // ---- super admin -------------------------------------------------------------------------------

    private UUID newLead(String communityName, int size) {
        String email = leadEmail();
        Map<String, Object> body = form(email);
        body.put("communityName", communityName);
        body.put("size", size);
        assertThat(api.post(PUBLIC, body).status()).isEqualTo(202);
        return leadId(email);
    }

    @Test
    void listsFiltersSearchesAndShowsLeads() {
        String marker = "Zeta" + TestData.unique();
        UUID one = newLead(marker + " Society", 50);
        UUID two = newLead("Other " + TestData.unique(), 60);
        assertThat(admin("PATCH", LEADS + "/" + two + "/status", Map.of("status", "CONTACTED")).status()).isEqualTo(200);

        ApiClient.Response search = adminGet(LEADS + "?q=" + marker.toLowerCase());
        assertThat(search.status()).as(search.body()).isEqualTo(200);
        assertThat(search.json().get("total").asInt()).isEqualTo(1);
        assertThat(search.json().get("items").get(0).get("id").asString()).isEqualTo(one.toString());
        assertThat(search.json().get("items").get(0).get("size").asInt()).isEqualTo(50);

        assertThat(adminGet(LEADS + "?q=%25").json().get("total").asInt()).as("LIKE wildcards are escaped").isZero();
        assertThat(adminGet(LEADS + "?status=CONTACTED&size=100").json().get("items").toString()).contains(two.toString()).doesNotContain(one.toString());
        assertThat(adminGet(LEADS + "?status=BOGUS").status()).isEqualTo(400);
        assertThat(adminGet(LEADS + "?sort=createdAt,asc").status()).isEqualTo(200);
        assertThat(adminGet(LEADS + "?sort=message,asc").status()).as("not whitelisted").isEqualTo(400);

        ApiClient.Response detail = adminGet(LEADS + "/" + one);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.json().get("message").asString()).startsWith("We would like a demo");
        assertThat(adminGet(LEADS + "/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    @Test
    void changesStatusAuditsAndRefusesConverted() {
        UUID id = newLead("Status Test " + TestData.unique(), 30);

        for (String status : List.of("CONTACTED", "DEMO_SCHEDULED", "LOST", "NEW")) {
            ApiClient.Response response = admin("PATCH", LEADS + "/" + id + "/status", Map.of("status", status));
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            assertThat(response.json().get("status").asString()).isEqualTo(status);
            assertThat(response.json().get("handledBy").asString()).isEqualTo(superAdmin.id().toString());
        }
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'LEAD_STATUS_CHANGED' and entity_id = ?", Long.class, id)).isEqualTo(4);

        ApiClient.Response converted = admin("PATCH", LEADS + "/" + id + "/status", Map.of("status", "CONVERTED"));
        assertThat(converted.status()).isEqualTo(409);
        assertThat(converted.code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(admin("PATCH", LEADS + "/" + id + "/status", Map.of("status", "CLOSED")).status()).isEqualTo(400);
        assertThat(admin("PATCH", LEADS + "/" + id + "/status", Map.of()).status()).isEqualTo(400);
        assertThat(admin("PATCH", LEADS + "/" + UUID.randomUUID() + "/status", Map.of("status", "LOST")).status()).isEqualTo(404);
    }

    @Test
    void convertsALeadToACommunityThroughAPrefilledDraft() {
        UUID id = newLead("Convert Me " + TestData.unique(), 120);

        ApiClient.Response draft = adminGet(LEADS + "/" + id + "/community-draft");

        assertThat(draft.status()).as(draft.body()).isEqualTo(200);
        var request = draft.json().get("request");
        assertThat(request.get("name").asString()).startsWith("Convert Me");
        assertThat(request.get("ownerName").asString()).isEqualTo("Priya Sharma");
        assertThat(request.get("ownerEmail").asString()).isEqualTo(jdbc.queryForObject("select email from leads where id = ?", String.class, id));
        assertThat(request.get("contactPhone").asString()).isEqualTo("+91 98765 43210");
        assertThat(request.get("leadId").asString()).isEqualTo(id.toString());
        assertThat(draft.json().get("suggestedPlanCode").isNull()).as("a plan fitting 120 members is suggested or none").isFalse();
        assertThat(request.get("planId").asString()).isNotBlank();

        // Complete the draft (the admin supplies what the lead did not) and create.
        Map<String, Object> create = new LinkedHashMap<>();
        request.properties().forEach(e -> {
            if (!e.getValue().isNull()) create.put(e.getKey(), e.getValue().isNumber() ? e.getValue().asInt() : e.getValue().asString());
        });
        ApiClient.Response created = adminPost(ADMIN + "/communities", create);
        assertThat(created.status()).as(created.body()).isEqualTo(201);

        Map<String, Object> lead = jdbc.queryForMap("select status, converted_community_id, handled_by from leads where id = ?", id);
        assertThat(lead.get("status")).isEqualTo("CONVERTED");
        assertThat(lead.get("converted_community_id").toString()).isEqualTo(created.json().get("id").asString());
        assertThat(lead.get("handled_by")).isEqualTo(superAdmin.id());

        assertThat(adminGet(LEADS + "/" + id + "/community-draft").status()).as("already converted").isEqualTo(409);
        assertThat(admin("PATCH", LEADS + "/" + id + "/status", Map.of("status", "LOST")).status()).isEqualTo(409);
        Map<String, Object> again = new LinkedHashMap<>(create);
        again.put("ownerEmail", leadEmail());
        assertThat(adminPost(ADMIN + "/communities", again).status()).as("a lead converts once").isEqualTo(409);
    }
}

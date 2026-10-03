package com.amanahconnect.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.Community;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.TestData;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The member endpoints, with the cross-tenant harness applied to every one of them. */
class MemberIT extends AbstractTenantIT {

    static final String MEMBERS = "/api/v1/community/members";

    @org.springframework.beans.factory.annotation.Autowired com.amanahconnect.auth.JwtService jwtService;
    @org.springframework.beans.factory.annotation.Autowired com.amanahconnect.auth.UserRepository userRepository;

    private Map<String, Object> body(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", name);
        return body;
    }

    private Map<String, Object> full(String name, String email) {
        Map<String, Object> body = body(name);
        body.put("email", email);
        body.put("phone", "+91 98765 43210");
        body.put("group", "Block A");
        body.put("joinedOn", "2024-04-01");
        body.put("consentEmail", true);
        body.put("customFields", Map.of("vehicle_no", "MH12AB1234", "family_size", 4, "owner", true));
        return body;
    }

    private String email() {
        return "m-" + TestData.unique() + "@example.test";
    }

    private JsonNode createA(Map<String, Object> body) {
        ApiClient.Response response = asA("POST", MEMBERS, body);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json();
    }

    private UUID idOf(JsonNode node) {
        return UUID.fromString(node.get("id").asString());
    }

    private long dbCount(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void setPlanLimits(Community community, Map<String, Object> limits) {
        Plan plan = data.customPlan("Limited " + TestData.unique(), limits, Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", plan.getId(), community.getId());
    }

    private void invoice(Community community, UUID memberId, String status, String amount, String paid) {
        jdbc.update("insert into invoices (id, community_id, member_id, invoice_no, kind, amount, amount_paid, due_date, status)"
                        + " values (gen_random_uuid(), ?, ?, ?, 'MEMBERSHIP', ?::numeric, ?::numeric, current_date, ?)",
                community.getId(), memberId, "T-" + TestData.unique(), amount, paid, status);
    }

    // ---- create -------------------------------------------------------------------------------------------

    @Test
    void createsAMemberWithAGeneratedNumberAndAnAuditEntryThatHoldsNoPersonalData() {
        String email = email();
        JsonNode created = createA(full("Asha Rao", email));

        String prefix = jdbc.queryForObject("select upper(substr(regexp_replace(slug, '[^a-z0-9]', '', 'g'), 1, 8)) from communities where id = ?", String.class, communityA.getId());
        assertThat(created.get("memberNo").asString()).isEqualTo(prefix + "-0001");
        assertThat(created.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.get("fullName").asString()).isEqualTo("Asha Rao");
        assertThat(created.get("group").asString()).isEqualTo("Block A");
        assertThat(created.get("joinedOn").asString()).isEqualTo("2024-04-01");
        assertThat(created.get("consentEmail").asBoolean()).isTrue();
        assertThat(created.get("customFields").get("family_size").asInt()).isEqualTo(4);
        assertThat(createA(body("Second")).get("memberNo").asString()).isEqualTo(prefix + "-0002");
        assertThat(createA(body("Third")).get("joinedOn").asString()).as("joined date defaults to today").isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString());

        List<Map<String, Object>> audit = jdbc.queryForList("select * from audit_logs where action = 'MEMBER_CREATED' and entity_id = ?", idOf(created));
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("community_id")).isEqualTo(communityA.getId());
        assertThat(audit.get(0).get("actor_user_id")).isEqualTo(adminA.id());
        assertThat(audit.get(0).toString()).doesNotContain(email).doesNotContain("Asha").doesNotContain("98765");
    }

    @Test
    void anyMemberNumberSentByTheClientIsIgnoredAndTakenNumbersAreSkipped() {
        String prefix = jdbc.queryForObject("select upper(substr(regexp_replace(slug, '[^a-z0-9]', '', 'g'), 1, 8)) from communities where id = ?", String.class, communityA.getId());
        jdbc.update("insert into members (id, community_id, member_no, full_name) values (gen_random_uuid(), ?, ?, 'Imported earlier')", communityA.getId(), prefix + "-0002");
        Map<String, Object> forged = body("Forger");
        forged.put("memberNo", "CHOSEN-1");

        assertThat(createA(forged).get("memberNo").asString()).isEqualTo(prefix + "-0001");
        assertThat(createA(body("Next")).get("memberNo").asString()).as("0002 is taken, so 0003").isEqualTo(prefix + "-0003");
    }

    @Test
    void validatesInput() {
        assertThat(asA("POST", MEMBERS, Map.of()).status()).isEqualTo(400);
        assertThat(asA("POST", MEMBERS, body(" ")).status()).isEqualTo(400);
        Map<String, Object> bad = body("X");
        bad.put("email", "not-an-email");
        assertThat(asA("POST", MEMBERS, bad).status()).isEqualTo(400);
        bad = body("X");
        bad.put("phone", "call me");
        assertThat(asA("POST", MEMBERS, bad).status()).isEqualTo(400);
        bad = body("X");
        bad.put("joinedOn", LocalDate.now().plusDays(3).toString());
        assertThat(asA("POST", MEMBERS, bad).status()).isEqualTo(400);
        bad = body("x".repeat(151));
        assertThat(asA("POST", MEMBERS, bad).status()).isEqualTo(400);

        for (Map<String, Object> fields : List.<Map<String, Object>>of(
                Map.of("Bad Key", "x"), Map.of("1abc", "x"), Map.of("nested", Map.of("a", 1)), Map.of("list", List.of(1)), Map.of("long", "x".repeat(501)))) {
            Map<String, Object> request = body("X");
            request.put("customFields", fields);
            ApiClient.Response response = asA("POST", MEMBERS, request);
            assertThat(response.status()).as(fields.toString()).isEqualTo(400);
            assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        }
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 21; i++) many.put("f" + i, "v");
        Map<String, Object> request = body("X");
        request.put("customFields", many);
        assertThat(asA("POST", MEMBERS, request).status()).isEqualTo(400);
        assertThat(dbCount("select count(*) from members where community_id = ?", communityA.getId())).isZero();
    }

    // ---- duplicate email -----------------------------------------------------------------------------------

    @Test
    void aDuplicateEmailInTheSameCommunityIsRefusedUnlessAllowedAndIgnoresCase() {
        String email = email();
        JsonNode first = createA(full("First", email));

        ApiClient.Response duplicate = asA("POST", MEMBERS, full("Second", email.toUpperCase()));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("DUPLICATE_EMAIL");
        assertThat(duplicate.json().get("existingMemberNo").asString()).isEqualTo(first.get("memberNo").asString());

        Map<String, Object> shared = full("Spouse", email);
        shared.put("allowDuplicateEmail", true);
        assertThat(asA("POST", MEMBERS, shared).status()).as("a household may share an address on purpose").isEqualTo(201);

        assertThat(asB("POST", MEMBERS, full("Other community", email)).status()).as("another community is a different world").isEqualTo(201);
    }

    @Test
    void updatingToAnotherMembersEmailIsRefusedAndADeletedMembersEmailIsFree() {
        String taken = email();
        JsonNode holder = createA(full("Holder", taken));
        JsonNode other = createA(full("Other", email()));

        ApiClient.Response clash = asA("PATCH", MEMBERS + "/" + idOf(other), Map.of("email", taken));
        assertThat(clash.status()).isEqualTo(409);
        assertThat(clash.code()).isEqualTo("DUPLICATE_EMAIL");
        assertThat(asA("PATCH", MEMBERS + "/" + idOf(holder), Map.of("email", taken, "fullName", "Holder Renamed")).status()).as("their own address is fine").isEqualTo(200);

        assertThat(asA("DELETE", MEMBERS + "/" + idOf(holder), null).status()).isEqualTo(204);
        assertThat(asA("PATCH", MEMBERS + "/" + idOf(other), Map.of("email", taken)).status()).as("freed by the deletion").isEqualTo(200);
    }

    // ---- list, search, filter -------------------------------------------------------------------------------

    @Test
    void searchesFiltersSortsAndPaginates() {
        String marker = "Zq" + TestData.unique();
        Map<String, Object> a = full(marker + " Alpha", "alpha-" + TestData.unique() + "@example.test");
        a.put("phone", "+91 98765 11111");
        Map<String, Object> b = full(marker + " Beta", "beta-" + TestData.unique() + "@example.test");
        b.put("phone", "022-2222-3333");
        b.put("group", "block b");
        Map<String, Object> c = full(marker + " Gamma", "gamma-" + TestData.unique() + "@example.test");
        c.put("group", "Block A");
        JsonNode alpha = createA(a);
        JsonNode beta = createA(b);
        JsonNode gamma = createA(c);
        asA("POST", MEMBERS + "/" + idOf(gamma) + "/deactivate", Map.of("reason", "moved away"));

        assertThat(names(asA("GET", MEMBERS + "?q=" + marker.toLowerCase() + "&sort=name,desc", null))).containsExactly(marker + " Gamma", marker + " Beta", marker + " Alpha");
        assertThat(names(asA("GET", MEMBERS + "?q=" + marker + "&status=INACTIVE", null))).containsExactly(marker + " Gamma");
        assertThat(names(asA("GET", MEMBERS + "?q=" + marker + "&group=BLOCK%20B", null))).as("group filter ignores case").containsExactly(marker + " Beta");
        assertThat(names(asA("GET", MEMBERS + "?q=" + alpha.get("memberNo").asString(), null))).as("by member number").containsExactly(marker + " Alpha");
        assertThat(names(asA("GET", MEMBERS + "?q=" + a.get("email"), null))).as("by email").containsExactly(marker + " Alpha");
        assertThat(names(asA("GET", MEMBERS + "?q=98765%2011111", null))).as("by phone as typed").contains(marker + " Alpha");
        assertThat(names(asA("GET", MEMBERS + "?q=9876511111", null))).as("by phone digits only").contains(marker + " Alpha");
        assertThat(names(asA("GET", MEMBERS + "?q=2222333", null))).as("by phone digits, any formatting").containsExactly(marker + " Beta");
        assertThat(names(asA("GET", MEMBERS + "?q=%25", null))).as("LIKE wildcards are plain characters").isEmpty();
        assertThat(names(asA("GET", MEMBERS + "?q=nobody-" + TestData.unique(), null))).isEmpty();

        ApiClient.Response paged = asA("GET", MEMBERS + "?q=" + marker + "&sort=name&size=2&page=1", null);
        assertThat(paged.json().get("total").asInt()).isEqualTo(3);
        assertThat(paged.json().get("items").size()).isEqualTo(1);
        assertThat(paged.json().get("page").asInt()).isEqualTo(1);

        assertThat(asA("GET", MEMBERS + "?sort=email", null).status()).as("not whitelisted").isEqualTo(400);
        assertThat(asA("GET", MEMBERS + "?size=101", null).status()).isEqualTo(400);
        assertThat(asA("GET", MEMBERS + "?status=BOGUS", null).status()).isEqualTo(400);
        assertThat(beta.get("id")).isNotNull();
    }

    private List<String> names(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        response.json().get("items").forEach(n -> names.add(n.get("fullName").asString()));
        return names;
    }

    // ---- update, status, delete -------------------------------------------------------------------------------

    @Test
    void updatesPartiallyAndClearsOptionalFieldsWithEmptyStrings() {
        JsonNode created = createA(full("Asha Rao", email()));
        UUID id = idOf(created);

        ApiClient.Response renamed = asA("PATCH", MEMBERS + "/" + id, Map.of("fullName", "Asha R. Rao", "customFields", Map.of("flat", "B-12")));
        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        assertThat(renamed.json().get("fullName").asString()).isEqualTo("Asha R. Rao");
        assertThat(renamed.json().get("phone").asString()).as("untouched").isEqualTo("+91 98765 43210");
        assertThat(renamed.json().get("customFields").size()).as("custom fields are replaced as a whole").isEqualTo(1);

        ApiClient.Response cleared = asA("PATCH", MEMBERS + "/" + id, Map.of("phone", "", "group", "", "email", "", "consentEmail", false));
        assertThat(cleared.status()).as(cleared.body()).isEqualTo(200);
        assertThat(cleared.json().get("phone").isNull()).isTrue();
        assertThat(cleared.json().get("group").isNull()).isTrue();
        assertThat(cleared.json().get("email").isNull()).isTrue();
        assertThat(cleared.json().get("consentEmail").asBoolean()).isFalse();

        assertThat(asA("PATCH", MEMBERS + "/" + id, Map.of("fullName", " ")).status()).isEqualTo(400);
        assertThat(asA("PATCH", MEMBERS + "/" + id, Map.of("phone", "abc")).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'MEMBER_UPDATED' and entity_id = ?", Long.class, id)).isEqualTo(2);
    }

    @Test
    void deactivatingNeedsAReasonAndActivatingRestores() {
        UUID id = idOf(createA(body("Asha")));

        assertThat(asA("POST", MEMBERS + "/" + id + "/deactivate", Map.of()).status()).as("reason required").isEqualTo(400);
        assertThat(asA("POST", MEMBERS + "/" + id + "/deactivate", Map.of("reason", " ")).status()).isEqualTo(400);
        ApiClient.Response off = asA("POST", MEMBERS + "/" + id + "/deactivate", Map.of("reason", "Moved out"));
        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(off.json().get("status").asString()).isEqualTo("INACTIVE");
        assertThat(off.json().get("statusReason").asString()).isEqualTo("Moved out");
        assertThat(off.json().get("statusChangedAt").isNull()).isFalse();

        ApiClient.Response again = asA("POST", MEMBERS + "/" + id + "/deactivate", Map.of("reason", "Again"));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("INVALID_STATE_TRANSITION");

        ApiClient.Response on = asA("POST", MEMBERS + "/" + id + "/activate", null);
        assertThat(on.status()).as(on.body()).isEqualTo(200);
        assertThat(on.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(asA("POST", MEMBERS + "/" + id + "/activate", Map.of("reason", "x")).status()).isEqualTo(409);

        List<Map<String, Object>> audit = jdbc.queryForList("select action, after::text as after from audit_logs where entity_id = ? and action like 'MEMBER_%ACTIVATED' order by created_at", id);
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0).get("after").toString()).contains("Moved out");
    }

    @Test
    void deletionIsSoftHidesTheMemberAndKeepsTheRow() {
        UUID id = idOf(createA(body("Leaver")));

        assertThat(asA("DELETE", MEMBERS + "/" + id, null).status()).isEqualTo(204);

        assertThat(asA("GET", MEMBERS + "/" + id, null).status()).isEqualTo(404);
        assertThat(asA("GET", MEMBERS + "?q=Leaver", null).json().get("total").asInt()).isZero();
        assertThat(asA("DELETE", MEMBERS + "/" + id, null).status()).as("already gone").isEqualTo(404);
        assertThat(asA("PATCH", MEMBERS + "/" + id, Map.of("fullName", "Back")).status()).isEqualTo(404);
        Map<String, Object> row = jdbc.queryForMap("select deleted_at, deleted_by from members where id = ?", id);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("deleted_by")).isEqualTo(adminA.id());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'MEMBER_DELETED' and entity_id = ?", Long.class, id)).isOne();
    }

    @Test
    void aMemberWithUnpaidInvoicesCannotBeDeletedUnlessForcedWithAReason() {
        JsonNode created = createA(body("Debtor"));
        UUID id = idOf(created);
        invoice(communityA, id, "ISSUED", "1000.00", "0");
        invoice(communityA, id, "PARTIAL", "500.50", "100.25");
        invoice(communityA, id, "PAID", "300.00", "300.00");
        invoice(communityA, id, "CANCELLED", "300.00", "0");

        ApiClient.Response blocked = asA("DELETE", MEMBERS + "/" + id, null);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.code()).isEqualTo("MEMBER_HAS_UNPAID_INVOICES");
        assertThat(blocked.json().get("unpaidInvoices").asInt()).isEqualTo(2);
        assertThat(blocked.json().get("outstanding").asString()).isEqualTo("1400.25");
        assertThat(asA("DELETE", MEMBERS + "/" + id + "?force=true", null).status()).as("force needs a reason").isEqualTo(400);
        assertThat(asA("GET", MEMBERS + "/" + id, null).status()).as("still there").isEqualTo(200);

        ApiClient.Response forced = asA("DELETE", MEMBERS + "/" + id + "?force=true&reason=Left%20the%20community", null);
        assertThat(forced.status()).isEqualTo(204);
        assertThat(asA("GET", MEMBERS + "/" + id, null).status()).isEqualTo(404);
        assertThat(dbCount("select count(*) from invoices where member_id = ?", id)).as("nothing financial is removed").isEqualTo(4);
        Map<String, Object> audit = jdbc.queryForMap("select after::text as after from audit_logs where action = 'MEMBER_DELETED' and entity_id = ?", id);
        assertThat(audit.get("after").toString()).contains("\"forced\": true").contains("Left the community").contains("1400.25");
        assertThat(jdbc.queryForObject("select delete_reason from members where id = ?", String.class, id)).isEqualTo("Left the community");
    }

    @Test
    void paidAndCancelledInvoicesDoNotBlockDeletion() {
        UUID id = idOf(createA(body("Settled")));
        invoice(communityA, id, "PAID", "300.00", "300.00");
        invoice(communityA, id, "CANCELLED", "300.00", "0");
        assertThat(asA("DELETE", MEMBERS + "/" + id, null).status()).isEqualTo(204);
    }

    // ---- plan limit --------------------------------------------------------------------------------------------

    @Test
    void creationStopsAtThePlansMemberLimitAndADeletionFreesASlot() {
        setPlanLimits(communityA, Map.of("max_members", 2));
        JsonNode first = createA(body("One"));
        createA(body("Two"));

        ApiClient.Response over = asA("POST", MEMBERS, body("Three"));
        assertThat(over.status()).isEqualTo(402);
        assertThat(over.code()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        assertThat(over.json().get("limitValue").asInt()).isEqualTo(2);
        assertThat(dbCount("select count(*) from members where community_id = ?", communityA.getId())).isEqualTo(2);
        assertThat(dbCount("select last_value from document_counters where community_id = ? and counter_type = 'MEMBER'", communityA.getId())).as("the refused create gave its number back").isEqualTo(2);

        assertThat(asA("DELETE", MEMBERS + "/" + idOf(first), null).status()).isEqualTo(204);
        assertThat(asA("POST", MEMBERS, body("Three")).status()).isEqualTo(201);
        assertThat(asA("POST", MEMBERS, body("Four")).status()).isEqualTo(402);
    }

    @Test
    void concurrentCreatesCannotExceedTheLimit() throws Exception {
        setPlanLimits(communityA, Map.of("max_members", 3));
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String name = "Racer " + i;
                results.add(pool.submit(() -> asA("POST", MEMBERS, body(name)).status()));
            }
            int created = 0;
            int refused = 0;
            for (Future<Integer> f : results) {
                int status = f.get();
                if (status == 201) created++;
                else if (status == 402) refused++;
            }
            assertThat(created).isEqualTo(3);
            assertThat(refused).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(dbCount("select count(*) from members where community_id = ?", communityA.getId())).isEqualTo(3);
        assertThat(dbCount("select count(distinct member_no) from members where community_id = ?", communityA.getId())).as("numbers are unique").isEqualTo(3);
    }

    // ---- welcome email -----------------------------------------------------------------------------------------------

    @Test
    void sendsAWelcomeEmailOnlyWithAnAddressConsentAndTheSettingOn() {
        String email = email();
        createA(full("Welcomed", email));
        List<Map<String, Object>> queued = jdbc.queryForList("select * from email_outbox where to_email = ? and template = 'member-welcome'", email);
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).get("community_id")).as("on the community's own email quota").isEqualTo(communityA.getId());
        assertThat(queued.get(0).get("payload").toString()).contains("Welcomed");

        String noConsent = email();
        Map<String, Object> body = full("No consent", noConsent);
        body.put("consentEmail", false);
        createA(body);
        createA(body("No address"));
        assertThat(dbCount("select count(*) from email_outbox where to_email = ?", noConsent)).isZero();

        jdbc.update("update notification_settings set send_welcome = false where community_id = ?", communityA.getId());
        String off = email();
        createA(full("Setting off", off));
        assertThat(dbCount("select count(*) from email_outbox where to_email = ?", off)).isZero();
    }

    @Test
    void aMemberIsStillCreatedWhenTheEmailQuotaIsUsedUp() {
        setPlanLimits(communityA, Map.of("emails_per_month", 0));
        String email = email();

        ApiClient.Response response = asA("POST", MEMBERS, full("Quota", email));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(dbCount("select count(*) from email_outbox where to_email = ?", email)).isZero();
    }

    // ---- counts and export ------------------------------------------------------------------------------------

    @Test
    void dashboardCounts() {
        UUID one = idOf(createA(body("One")));
        createA(body("Two"));
        UUID three = idOf(createA(body("Three")));
        UUID gone = idOf(createA(body("Gone")));
        asA("POST", MEMBERS + "/" + three + "/deactivate", Map.of("reason", "x"));
        asA("DELETE", MEMBERS + "/" + gone, null);
        jdbc.update("update members set created_at = now() - interval '400 days' where id = ?", one);
        jdbc.update("insert into member_invites (id, community_id, token_hash, expires_at, created_by) values (gen_random_uuid(), ?, ?, now() + interval '1 day', ?)", communityA.getId(), "a".repeat(64), adminA.id());
        jdbc.update("insert into member_registrations (id, community_id, invite_id, full_name, email) select gen_random_uuid(), community_id, id, 'Waiting', 'w@example.test' from member_invites where community_id = ?", communityA.getId());

        JsonNode counts = asA("GET", MEMBERS + "/counts", null).json();

        assertThat(counts.get("total").asInt()).isEqualTo(3);
        assertThat(counts.get("active").asInt()).isEqualTo(2);
        assertThat(counts.get("inactive").asInt()).isEqualTo(1);
        assertThat(counts.get("newThisMonth").asInt()).as("one was created long ago, one deleted").isEqualTo(2);
        assertThat(counts.get("pendingRegistrations").asInt()).isEqualTo(1);
        assertThat(asB("GET", MEMBERS + "/counts", null).json().get("total").asInt()).as("B's own numbers").isZero();
    }

    @Test
    void exportsCsvWithFiltersGuardsAgainstFormulasAndAudits() {
        String marker = "Exp" + TestData.unique();
        createA(full(marker + " Alpha", email()));
        Map<String, Object> evil = full("=HYPERLINK(\"http://evil\")" + marker, email());
        evil.put("group", "Block B");
        createA(evil);
        UUID gone = idOf(createA(body(marker + " Deleted")));
        asA("DELETE", MEMBERS + "/" + gone, null);
        data.member(communityB);

        ApiClient.Response response = asA("GET", MEMBERS + "/export?q=" + marker, null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/csv");
        assertThat(response.header("Content-Disposition")).contains("attachment").contains(".csv");
        String csv = response.body();
        assertThat(csv).startsWith("﻿member_no,full_name,email,phone,group,status,joined_on,consent_email,created_at,custom_fields\r\n");
        assertThat(csv.split("\r\n")).hasSize(3);
        assertThat(csv).contains(marker + " Alpha").contains("\"'=HYPERLINK").doesNotContain("Deleted");
        assertThat(csv).contains("vehicle_no");
        assertThat(asA("GET", MEMBERS + "/export?q=" + marker + "&group=block%20b", null).body().split("\r\n")).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'MEMBERS_EXPORTED' and community_id = ?", Long.class, communityA.getId())).isEqualTo(2);
    }

    @Test
    void exportStreamsLargeCommunitiesAcrossPages() {
        jdbc.update("insert into members (id, community_id, member_no, full_name, created_at) select gen_random_uuid(), ?, 'BULK-' || g, 'Bulk ' || g, now() - (g || ' seconds')::interval from generate_series(1, 1203) g", communityA.getId());

        ApiClient.Response response = asA("GET", MEMBERS + "/export", null);

        assertThat(response.status()).isEqualTo(200);
        String[] lines = response.body().split("\r\n");
        assertThat(lines).hasSize(1204);
        assertThat(java.util.Arrays.stream(lines).skip(1).map(l -> l.split(",")[0]).distinct().count()).as("no row repeated or skipped across page boundaries").isEqualTo(1203);
    }

    // ---- tenant isolation: every endpoint ----------------------------------------------------------------------

    @Test
    void communityAdminCannotReachAnotherCommunitysMembers() {
        UUID memberB = data.member(communityB).getId();
        data.member(communityA);

        assertListHides(MEMBERS, memberB);
        assertCrossTenantRead(MEMBERS + "/" + memberB);
        assertCrossTenantUpdate("PATCH", MEMBERS + "/" + memberB, Map.of("fullName", "Hijacked"), () ->
                assertThat(jdbc.queryForObject("select full_name from members where id = ?", String.class, memberB)).doesNotContain("Hijacked"));

        jdbc.update("update members set status = 'INACTIVE' where id = ?", memberB);
        assertCrossTenantUpdate("POST", MEMBERS + "/" + memberB + "/activate", Map.of(), () ->
                assertThat(jdbc.queryForObject("select status from members where id = ?", String.class, memberB)).isEqualTo("INACTIVE"));
        assertCrossTenantUpdate("POST", MEMBERS + "/" + memberB + "/deactivate", Map.of("reason", "x"), () ->
                assertThat(jdbc.queryForObject("select status from members where id = ?", String.class, memberB)).isEqualTo("ACTIVE"));
        assertCrossTenantDelete(MEMBERS + "/" + memberB, () ->
                assertThat(jdbc.queryForObject("select deleted_at from members where id = ?", java.sql.Timestamp.class, memberB)).isNull());

        assertCreateCannotTargetOtherTenant(MEMBERS, body("Created by A"), r -> idOf(r.json()), MEMBERS + "/%s");
        asB("POST", MEMBERS, body("Another of B"));
        assertTenantSingleton("GET", MEMBERS + "/counts", null, () -> asB("GET", MEMBERS + "/counts", null).body());
        assertTenantSingleton("GET", MEMBERS + "/export", null, () -> asB("GET", MEMBERS + "/export", null).body());
        assertThat(asA("GET", MEMBERS + "/export", null).body()).as("A's export holds none of B's members").doesNotContain("Another of B");
    }

    @Test
    void aMemberOfAnotherCommunityLooksExactlyLikeAMissingOne() {
        UUID memberB = data.member(communityB).getId();

        ApiClient.Response foreign = asA("DELETE", MEMBERS + "/" + memberB + "?force=true&reason=x", null);
        ApiClient.Response missing = asA("DELETE", MEMBERS + "/" + UUID.randomUUID() + "?force=true&reason=x", null);

        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.json().get("detail")).isEqualTo(missing.json().get("detail"));
        assertThat(foreign.json().get("code")).isEqualTo(missing.json().get("code"));
    }

    @Test
    void aSuspendedCommunityCanReadAndExportButNotWrite() {
        UUID id = idOf(createA(body("Existing")));
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());

        assertThat(asA("GET", MEMBERS, null).status()).isEqualTo(200);
        assertThat(asA("GET", MEMBERS + "/export", null).status()).isEqualTo(200);
        ApiClient.Response write = asA("POST", MEMBERS, body("Late"));
        assertThat(write.status()).isEqualTo(403);
        assertThat(write.code()).isEqualTo("COMMUNITY_SUSPENDED");
        assertThat(asA("DELETE", MEMBERS + "/" + id, null).status()).isEqualTo(403);
    }

    @Test
    void onlyACommunityAdminMayCallTheMemberEndpoints() {
        assertThat(api.get(MEMBERS).status()).isEqualTo(401);
        var superAdmin = users.superAdmin();
        String superToken = ApiClient.bearer(jwtService.issueAccessToken(userRepository.findById(superAdmin.id()).orElseThrow(), false));
        assertThat(api.get(MEMBERS, "Authorization", superToken).status()).isEqualTo(403);
    }
}

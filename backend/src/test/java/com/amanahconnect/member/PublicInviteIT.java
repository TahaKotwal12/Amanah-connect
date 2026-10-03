package com.amanahconnect.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.InMemoryObjectStorage;
import com.amanahconnect.support.TestData;
import com.amanahconnect.support.AuthTestUsers.TestUser;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** What a person holding (or guessing) an invite link can see and do: nothing beyond the one community's form. */
class PublicInviteIT extends AbstractTenantIT {

    private static final String PUBLIC = InviteIT.PUBLIC;

    @Autowired InMemoryObjectStorage storage;

    private JsonNode createInvite(Session session, Map<String, Object> body) {
        ApiClient.Response response = call(session, "POST", InviteIT.INVITES, body);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json();
    }

    private String tokenOf(JsonNode created) {
        String link = created.get("link").asString();
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private String tokenA() {
        return tokenOf(createInvite(sessionA, Map.of()));
    }

    private Map<String, Object> form(String name, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", name);
        body.put("email", email);
        return body;
    }

    private String email() {
        return "p-" + TestData.unique() + "@example.test";
    }

    private long registrations(UUID communityId) {
        return jdbc.queryForObject("select count(*) from member_registrations where community_id = ?", Long.class, communityId);
    }

    private long usedCount(String token) {
        return jdbc.queryForObject("select used_count from member_invites where token_hash = ?", Long.class, com.amanahconnect.auth.Tokens.sha256Hex(token));
    }

    private void setInvite(String token, String set) {
        jdbc.update("update member_invites set " + set + " where token_hash = ?", com.amanahconnect.auth.Tokens.sha256Hex(token));
    }

    // ---- what the page shows ---------------------------------------------------------------------------------------

    @Test
    void aValidLinkShowsOnlyTheCommunitysNameLogoAndForm() {
        String logoKey = "communities/" + communityA.getId() + "/logo/" + UUID.randomUUID() + ".png";
        storage.put(logoKey, "image/png", 100);
        jdbc.update("update communities set logo_key = ?, settings = jsonb_build_object('memberGroupLabel', 'Flat') where id = ?", logoKey, communityA.getId());
        String token = tokenA();

        ApiClient.Response response = api.get(PUBLIC + token);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("Cache-Control")).contains("no-store");
        JsonNode view = response.json();
        assertThat(view.get("communityName").asString()).isEqualTo(communityA.getName());
        assertThat(view.get("logoUrl").asString()).contains(logoKey);
        assertThat(view.get("groupLabel").asString()).isEqualTo("Flat");
        assertThat(view.get("defaultGroup").isNull()).isTrue();
        List<String> fields = new ArrayList<>();
        view.get("fields").forEach(f -> fields.add(f.get("name").asString()));
        assertThat(fields).containsExactly("fullName", "email", "phone", "group", "consentEmail");
        assertThat(view.get("fields").get(0).get("required").asBoolean()).isTrue();
        assertThat(view.get("fields").get(3).get("label").asString()).as("the community's own word for a group").isEqualTo("Flat");

        List<String> keys = new ArrayList<>();
        view.properties().forEach(e -> keys.add(e.getKey()));
        assertThat(keys).containsExactlyInAnyOrder("communityName", "logoUrl", "groupLabel", "defaultGroup", "fields");
        // The signed logo URL carries the storage key, which holds the community's id (not a secret: it grants nothing).
        String withoutLogo = response.body().replace(view.get("logoUrl").asString(), "");
        assertThat(withoutLogo).doesNotContain(communityA.getId().toString()).doesNotContain(communityA.getSlug()).doesNotContain(adminA.email()).doesNotContain(communityB.getName());
    }

    @Test
    void aLinkWithAFixedGroupHidesTheGroupField() {
        String token = tokenOf(createInvite(sessionA, Map.of("defaultGroup", "Block C")));

        JsonNode view = api.get(PUBLIC + token).json();

        assertThat(view.get("defaultGroup").asString()).isEqualTo("Block C");
        List<String> fields = new ArrayList<>();
        view.get("fields").forEach(f -> fields.add(f.get("name").asString()));
        assertThat(fields).doesNotContain("group");
    }

    // ---- invalid links all look the same ----------------------------------------------------------------------------

    @Test
    void everyKindOfBadLinkGetsTheSameGenericAnswer() {
        String expired = tokenA();
        setInvite(expired, "expires_at = now() - interval '1 minute'");
        String revoked = tokenA();
        setInvite(revoked, "revoked_at = now()");
        String usedUp = tokenOf(createInvite(sessionA, Map.of("maxUses", 1)));
        setInvite(usedUp, "used_count = 1");
        String suspended = tokenOf(createInvite(sessionB, Map.of()));
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityB.getId());

        List<String> bad = List.of(expired, revoked, usedUp, suspended, "A".repeat(43), "short", "x".repeat(200), "not a token", "a".repeat(42) + "!");
        ApiClient.Response reference = api.get(PUBLIC + "A".repeat(43));
        assertThat(reference.status()).isEqualTo(404);
        for (String token : bad) {
            ApiClient.Response view = api.get(PUBLIC + token.replace(" ", "%20"));
            assertThat(view.status()).as(token).isEqualTo(404);
            assertThat(view.code()).as(token).isEqualTo("INVITE_UNAVAILABLE");
            assertThat(view.json().get("detail")).as(token).isEqualTo(reference.json().get("detail"));
            assertThat(view.json().get("title")).as(token).isEqualTo(reference.json().get("title"));
            assertThat(view.body()).as("nothing about the community").doesNotContain(communityA.getName()).doesNotContain(communityB.getName());

            ApiClient.Response post = api.post(PUBLIC + token.replace(" ", "%20") + "/register", form("Visitor", email()));
            assertThat(post.status()).as(token).isEqualTo(404);
            assertThat(post.code()).isEqualTo("INVITE_UNAVAILABLE");
        }
        assertThat(registrations(communityA.getId()) + registrations(communityB.getId())).isZero();
    }

    @Test
    void aPendingOrArchivedCommunitysLinkIsUnavailableToo() {
        String pending = tokenA();
        jdbc.update("update communities set status = 'PENDING' where id = ?", communityA.getId());
        assertThat(api.get(PUBLIC + pending).status()).isEqualTo(404);
        jdbc.update("update communities set status = 'ARCHIVED' where id = ?", communityA.getId());
        assertThat(api.get(PUBLIC + pending).status()).isEqualTo(404);
        jdbc.update("update communities set status = 'ACTIVE' where id = ?", communityA.getId());
        assertThat(api.get(PUBLIC + pending).status()).as("works again once active").isEqualTo(200);
    }

    @Test
    void aLinkNeverRevealsAnotherCommunity() {
        String tokenA = tokenA();
        String tokenB = tokenOf(createInvite(sessionB, Map.of()));

        assertThat(api.get(PUBLIC + tokenA).json().get("communityName").asString()).isEqualTo(communityA.getName());
        assertThat(api.get(PUBLIC + tokenB).json().get("communityName").asString()).isEqualTo(communityB.getName());

        Map<String, Object> forged = form("Visitor", email());
        forged.put("communityId", communityB.getId().toString());
        forged.put("community_id", communityB.getId().toString());
        forged.put("inviteId", UUID.randomUUID().toString());
        ApiClient.Response response = api.post(PUBLIC + tokenA + "/register?communityId=" + communityB.getId(), forged, "X-Community-Id", communityB.getId().toString());

        assertThat(response.status()).isEqualTo(202);
        assertThat(response.body()).isEqualTo("{\"received\":true}");
        assertThat(registrations(communityA.getId())).isOne();
        assertThat(registrations(communityB.getId())).as("the link decides the community, nothing the visitor sends").isZero();
    }

    @Test
    void theAnswerIsTheSameWhetherOrNotAnAddressIsAlreadyAMember() {
        String known = email();
        data.member(communityA);
        jdbc.update("insert into members (id, community_id, member_no, full_name, email) values (gen_random_uuid(), ?, 'KNOWN-1', 'Known', ?)", communityA.getId(), known);
        String token = tokenA();

        ApiClient.Response fresh = api.post(PUBLIC + token + "/register", form("New Person", email()));
        ApiClient.Response existing = api.post(PUBLIC + token + "/register", form("Known Person", known));

        assertThat(existing.status()).isEqualTo(fresh.status());
        assertThat(existing.body()).isEqualTo(fresh.body());
        assertThat(registrations(communityA.getId())).as("the admin decides, the visitor learns nothing").isEqualTo(2);
    }

    // ---- registering --------------------------------------------------------------------------------------------------

    @Test
    void registeringRecordsAPendingRegistrationAndTellsTheAdmins() {
        TestUser secondAdmin = users.extraAdminOf(communityA);
        String token = tokenA();
        String address = email();
        Map<String, Object> body = form("  Asha\u0000 \n Rao ", address);
        body.put("phone", "+91 98765 43210");
        body.put("group", "Block A");
        body.put("consentEmail", true);

        ApiClient.Response response = api.post(PUBLIC + token + "/register", body);

        assertThat(response.status()).as(response.body()).isEqualTo(202);
        assertThat(response.json().get("received").asBoolean()).isTrue();
        Map<String, Object> row = jdbc.queryForMap("select * from member_registrations where community_id = ?", communityA.getId());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("full_name")).as("control characters stripped, spaces collapsed").isEqualTo("Asha Rao");
        assertThat(row.get("email").toString()).isEqualTo(address);
        assertThat(row.get("group_label")).isEqualTo("Block A");
        assertThat(row.get("consent_email")).isEqualTo(true);
        assertThat(usedCount(token)).isOne();

        for (String admin : List.of(adminA.email(), secondAdmin.email())) {
            List<Map<String, Object>> mail = jdbc.queryForList("select * from email_outbox where to_email = ? and template = 'member-registration-received'", admin);
            assertThat(mail).as(admin).hasSize(1);
            assertThat(mail.get(0).get("payload").toString()).contains("Asha Rao").contains(communityA.getName()).doesNotContain(address);
        }
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where to_email = ?", Long.class, address)).as("the applicant is not emailed yet").isZero();

        Map<String, Object> audit = jdbc.queryForMap("select * from audit_logs where action = 'MEMBER_REGISTRATION_RECEIVED' and community_id = ?", communityA.getId());
        assertThat(audit.get("actor_user_id")).isNull();
        assertThat(audit.toString()).doesNotContain(address).doesNotContain("Asha");
    }

    @Test
    void consentDefaultsToNoAndAFixedGroupWins() {
        String token = tokenOf(createInvite(sessionA, Map.of("defaultGroup", "Block C")));
        Map<String, Object> body = form("Visitor", email());
        body.put("group", "Sneaky Group");

        assertThat(api.post(PUBLIC + token + "/register", body).status()).isEqualTo(202);

        Map<String, Object> row = jdbc.queryForMap("select group_label, consent_email from member_registrations where community_id = ?", communityA.getId());
        assertThat(row.get("group_label")).isEqualTo("Block C");
        assertThat(row.get("consent_email")).isEqualTo(false);
    }

    @Test
    void aFilledHoneypotIsAcceptedSilentlyAndStoresNothing() {
        String token = tokenA();
        Map<String, Object> body = form("Bot", email());
        body.put("website", "http://spam.example");

        ApiClient.Response response = api.post(PUBLIC + token + "/register", body);

        assertThat(response.status()).isEqualTo(202);
        assertThat(response.body()).isEqualTo("{\"received\":true}");
        assertThat(registrations(communityA.getId())).isZero();
        assertThat(usedCount(token)).as("a bot does not use up the link").isZero();
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where template = 'member-registration-received' and to_email = ?", Long.class, adminA.email())).isZero();

        Map<String, Object> blank = form("Human", email());
        blank.put("website", "   ");
        assertThat(api.post(PUBLIC + token + "/register", blank).status()).isEqualTo(202);
        assertThat(registrations(communityA.getId())).isOne();
    }

    @Test
    void aSecondRegistrationWithAnAddressAlreadyWaitingChangesNothing() {
        String token = tokenA();
        String address = email();

        assertThat(api.post(PUBLIC + token + "/register", form("First", address)).status()).isEqualTo(202);
        ApiClient.Response again = api.post(PUBLIC + token + "/register", form("Again", address.toUpperCase()));

        assertThat(again.status()).isEqualTo(202);
        assertThat(registrations(communityA.getId())).isOne();
        assertThat(usedCount(token)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where template = 'member-registration-received' and to_email = ?", Long.class, adminA.email())).isOne();
    }

    @Test
    void validatesWhatTheVisitorSends() {
        String token = tokenA();
        assertThat(api.post(PUBLIC + token + "/register", Map.of()).status()).isEqualTo(400);
        assertThat(api.post(PUBLIC + token + "/register", Map.of("fullName", "No Email")).status()).isEqualTo(400);
        assertThat(api.post(PUBLIC + token + "/register", Map.of("email", email())).status()).isEqualTo(400);
        assertThat(api.post(PUBLIC + token + "/register", form("X", "not-an-email")).status()).isEqualTo(400);
        assertThat(api.post(PUBLIC + token + "/register", form("x".repeat(151), email())).status()).isEqualTo(400);
        Map<String, Object> badPhone = form("X", email());
        badPhone.put("phone", "<script>");
        assertThat(api.post(PUBLIC + token + "/register", badPhone).status()).isEqualTo(400);
        Map<String, Object> longGroup = form("X", email());
        longGroup.put("group", "g".repeat(101));
        assertThat(api.post(PUBLIC + token + "/register", longGroup).status()).isEqualTo(400);
        assertThat(api.post(PUBLIC + token + "/register", form("\u0000\u0001", email())).status()).as("only control characters").isEqualTo(400);
        Map<String, Object> huge = form("X", email());
        huge.put("website", "w".repeat(20_000));
        assertThat(api.post(PUBLIC + token + "/register", huge).status()).as("oversized body").isEqualTo(413);
        assertThat(registrations(communityA.getId())).isZero();
        assertThat(usedCount(token)).isZero();
    }

    @Test
    void aStaleAuthorizationHeaderDoesNotBreakThePublicEndpoints() {
        String token = tokenA();
        assertThat(api.get(PUBLIC + token, "Authorization", "Bearer not.a.token").status()).isEqualTo(200);
        assertThat(api.post(PUBLIC + token + "/register", form("Visitor", email()), "Authorization", "Bearer not.a.token").status()).isEqualTo(202);
    }

    // ---- expiry, maximum uses ------------------------------------------------------------------------------------------

    @Test
    void anExpiredLinkStopsWorkingForBothPages() {
        String token = tokenA();
        assertThat(api.post(PUBLIC + token + "/register", form("Before", email())).status()).isEqualTo(202);

        setInvite(token, "expires_at = now() - interval '1 second'");

        assertThat(api.get(PUBLIC + token).status()).isEqualTo(404);
        assertThat(api.post(PUBLIC + token + "/register", form("After", email())).status()).isEqualTo(404);
        assertThat(registrations(communityA.getId())).isOne();
    }

    @Test
    void aLinkStopsAtItsMaximumUses() {
        String token = tokenOf(createInvite(sessionA, Map.of("maxUses", 2)));

        assertThat(api.post(PUBLIC + token + "/register", form("One", email())).status()).isEqualTo(202);
        assertThat(api.get(PUBLIC + token).status()).isEqualTo(200);
        assertThat(api.post(PUBLIC + token + "/register", form("Two", email())).status()).isEqualTo(202);

        assertThat(api.get(PUBLIC + token).status()).as("used up").isEqualTo(404);
        ApiClient.Response third = api.post(PUBLIC + token + "/register", form("Three", email()));
        assertThat(third.status()).isEqualTo(404);
        assertThat(third.code()).isEqualTo("INVITE_UNAVAILABLE");
        assertThat(registrations(communityA.getId())).isEqualTo(2);
        assertThat(usedCount(token)).isEqualTo(2);
    }

    @Test
    void concurrentRegistrationsCannotExceedTheMaximumUses() throws Exception {
        String token = tokenOf(createInvite(sessionA, Map.of("maxUses", 3)));
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String name = "Racer " + i;
                String address = email();
                results.add(pool.submit(() -> api.post(PUBLIC + token + "/register", form(name, address)).status()));
            }
            int accepted = 0;
            int refused = 0;
            for (Future<Integer> f : results) {
                int status = f.get();
                if (status == 202) accepted++;
                else if (status == 404) refused++;
            }
            assertThat(accepted).isEqualTo(3);
            assertThat(refused).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(registrations(communityA.getId())).isEqualTo(3);
        assertThat(usedCount(token)).isEqualTo(3);
    }

    @Test
    void anEmailedLinkWorksOnce() {
        ApiClient.Response invited = asA("POST", InviteIT.INVITES + "/email", Map.of("email", email()));
        assertThat(invited.status()).isEqualTo(201);
        String payload = jdbc.queryForObject("select payload::text from email_outbox where template = 'member-invite' and community_id = ?", String.class, communityA.getId());
        String token = payload.substring(payload.indexOf("/join/") + 6, payload.indexOf("/join/") + 6 + 43);

        assertThat(api.post(PUBLIC + token + "/register", form("Invited", email())).status()).isEqualTo(202);
        assertThat(api.get(PUBLIC + token).status()).isEqualTo(404);
    }

    @Test
    void registeringThroughARevokedLinkIsRefusedEvenWithAnOldPage() {
        JsonNode created = createInvite(sessionA, Map.of());
        String token = tokenOf(created);
        assertThat(api.get(PUBLIC + token).status()).isEqualTo(200);
        asA("POST", InviteIT.INVITES + "/" + created.get("invite").get("id").asString() + "/revoke", null);

        assertThat(api.post(PUBLIC + token + "/register", form("Late", email())).status()).isEqualTo(404);
        assertThat(registrations(communityA.getId())).isZero();
    }
}

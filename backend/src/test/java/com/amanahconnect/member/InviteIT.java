package com.amanahconnect.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.auth.Tokens;
import com.amanahconnect.community.Community;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.TestData;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Invite links and the registration review queue (admin side), with the cross-tenant harness on every endpoint. */
class InviteIT extends AbstractTenantIT {

    static final String INVITES = "/api/v1/community/member-invites";
    static final String REGISTRATIONS = "/api/v1/community/registrations";
    static final String PUBLIC = "/api/v1/public/invites/";

    private JsonNode createInvite(Session session, Map<String, Object> body) {
        ApiClient.Response response = call(session, "POST", INVITES, body);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json();
    }

    private static String tokenOf(JsonNode created) {
        String link = created.get("link").asString();
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private ApiClient.Response register(String token, String name, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", name);
        body.put("email", email);
        body.put("phone", "+91 98765 43210");
        body.put("group", "Block A");
        body.put("consentEmail", true);
        return api.post(PUBLIC + token + "/register", body);
    }

    private String email() {
        return "r-" + TestData.unique() + "@example.test";
    }

    private UUID registrationId(Community community, String email) {
        return jdbc.queryForObject("select id from member_registrations where community_id = ? and email = ?", UUID.class, community.getId(), email);
    }

    /** A registration waiting in the given community, made through the real public flow. */
    private UUID pendingRegistration(Session session, Community community, String name, String email) {
        String token = tokenOf(createInvite(session, Map.of()));
        assertThat(register(token, name, email).status()).isEqualTo(202);
        return registrationId(community, email);
    }

    private void limitPlanA(int max) {
        Plan plan = data.customPlan("Limit " + max, Map.of("max_members", max), Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", plan.getId(), communityA.getId());
    }

    // ---- creating links -----------------------------------------------------------------------------------------

    @Test
    void createsALinkAndQrCodeShownOnceAndStoresOnlyTheHash() {
        JsonNode created = createInvite(sessionA, Map.of());

        String link = created.get("link").asString();
        String token = tokenOf(created);
        assertThat(link).startsWith("http://localhost:5173/join/");
        assertThat(token).matches("^[A-Za-z0-9_-]{43}$");
        byte[] png = Base64.getDecoder().decode(created.get("qrCodePngBase64").asString());
        assertThat(png).startsWith(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        assertThat(png.length).isGreaterThan(500);

        JsonNode invite = created.get("invite");
        assertThat(invite.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(invite.get("maxUses").asInt()).isEqualTo(50);
        assertThat(invite.get("usedCount").asInt()).isZero();
        assertThat(java.time.Instant.parse(invite.get("expiresAt").asString())).isBetween(java.time.Instant.now().plus(13, java.time.temporal.ChronoUnit.DAYS), java.time.Instant.now().plus(15, java.time.temporal.ChronoUnit.DAYS));

        UUID id = UUID.fromString(invite.get("id").asString());
        assertThat(jdbc.queryForObject("select token_hash from member_invites where id = ?", String.class, id)).isEqualTo(Tokens.sha256Hex(token));
        assertThat(jdbc.queryForObject("select count(*) from member_invites where token_hash = ?", Long.class, token)).as("the raw token is not stored").isZero();
        Map<String, Object> audit = jdbc.queryForMap("select * from audit_logs where action = 'MEMBER_INVITE_CREATED' and entity_id = ?", id);
        assertThat(audit.toString()).doesNotContain(token);
        assertThat(asA("GET", INVITES + "/" + id, null).body()).as("the link cannot be read back").doesNotContain(token).doesNotContain("link");
    }

    @Test
    void honoursExpiryUsesAndDefaultGroupAndValidatesThem() {
        JsonNode created = createInvite(sessionA, Map.of("expiresInDays", 7, "maxUses", 3, "defaultGroup", "  Block C "));

        JsonNode invite = created.get("invite");
        assertThat(invite.get("maxUses").asInt()).isEqualTo(3);
        assertThat(invite.get("defaultGroup").asString()).isEqualTo("Block C");
        assertThat(java.time.Instant.parse(invite.get("expiresAt").asString())).isBetween(java.time.Instant.now().plus(6, java.time.temporal.ChronoUnit.DAYS), java.time.Instant.now().plus(8, java.time.temporal.ChronoUnit.DAYS));

        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("expiresInDays", 0), Map.of("expiresInDays", 91), Map.of("maxUses", 0), Map.of("maxUses", 1001), Map.of("defaultGroup", "x".repeat(101)))) {
            assertThat(asA("POST", INVITES, bad).status()).as(bad.toString()).isEqualTo(400);
        }
        assertThat(asA("POST", INVITES, null).status()).as("no body means all defaults").isEqualTo(201);
    }

    @Test
    void invitesSomeoneByEmailWithASingleUseLink() {
        String address = email();

        ApiClient.Response response = asA("POST", INVITES + "/email", Map.of("email", address, "name", "Asha Rao", "defaultGroup", "Block A"));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode invite = response.json().get("invite");
        assertThat(invite.get("maxUses").asInt()).isEqualTo(1);
        assertThat(invite.get("invitedEmail").asString()).isEqualTo(address);
        assertThat(response.body()).as("the link is only in the email").doesNotContain("/join/");
        List<Map<String, Object>> mail = jdbc.queryForList("select * from email_outbox where to_email = ? and template = 'member-invite'", address);
        assertThat(mail).hasSize(1);
        assertThat(mail.get(0).get("community_id")).isEqualTo(communityA.getId());
        String payload = mail.get(0).get("payload").toString();
        assertThat(payload).contains("/join/").contains(communityA.getName()).contains("Asha Rao");
        String token = payload.substring(payload.indexOf("/join/") + 6, payload.indexOf("/join/") + 6 + 43);
        assertThat(api.get(PUBLIC + token).status()).as("the emailed link works").isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'MEMBER_INVITE_EMAILED' and community_id = ?", Long.class, communityA.getId())).isOne();

        assertThat(asA("POST", INVITES + "/email", Map.of("email", "bad")).status()).isEqualTo(400);
        assertThat(asA("POST", INVITES + "/email", Map.of()).status()).isEqualTo(400);
    }

    @Test
    void emailingAnInviteCountsAgainstTheEmailQuota() {
        Plan plan = data.customPlan("No mail", Map.of("emails_per_month", 0), Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", plan.getId(), communityA.getId());
        String address = email();

        ApiClient.Response response = asA("POST", INVITES + "/email", Map.of("email", address));

        assertThat(response.status()).isEqualTo(402);
        assertThat(response.code()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from member_invites where community_id = ?", Long.class, communityA.getId())).as("no link is left behind").isZero();
    }

    // ---- listing and revoking ----------------------------------------------------------------------------------------

    @Test
    void listsLinksByDerivedState() {
        UUID active = UUID.fromString(createInvite(sessionA, Map.of()).get("invite").get("id").asString());
        UUID expired = UUID.fromString(createInvite(sessionA, Map.of()).get("invite").get("id").asString());
        UUID usedUp = UUID.fromString(createInvite(sessionA, Map.of("maxUses", 1)).get("invite").get("id").asString());
        UUID revoked = UUID.fromString(createInvite(sessionA, Map.of()).get("invite").get("id").asString());
        jdbc.update("update member_invites set expires_at = now() - interval '1 minute' where id = ?", expired);
        jdbc.update("update member_invites set used_count = 1 where id = ?", usedUp);
        assertThat(asA("POST", INVITES + "/" + revoked + "/revoke", null).status()).isEqualTo(200);

        assertThat(ids(asA("GET", INVITES + "?state=ACTIVE", null))).containsExactly(active.toString());
        assertThat(ids(asA("GET", INVITES + "?state=EXPIRED", null))).containsExactly(expired.toString());
        assertThat(ids(asA("GET", INVITES + "?state=USED_UP", null))).containsExactly(usedUp.toString());
        assertThat(ids(asA("GET", INVITES + "?state=REVOKED", null))).containsExactly(revoked.toString());
        assertThat(ids(asA("GET", INVITES, null))).containsExactlyInAnyOrder(active.toString(), expired.toString(), usedUp.toString(), revoked.toString());
        assertThat(asA("GET", INVITES + "?state=BOGUS", null).status()).isEqualTo(400);
        assertThat(asA("GET", INVITES + "/" + expired, null).json().get("state").asString()).isEqualTo("EXPIRED");
    }

    private List<String> ids(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        response.json().get("items").forEach(n -> ids.add(n.get("id").asString()));
        return ids;
    }

    @Test
    void revokingStopsTheLinkAtOnce() {
        JsonNode created = createInvite(sessionA, Map.of());
        String token = tokenOf(created);
        UUID id = UUID.fromString(created.get("invite").get("id").asString());
        assertThat(api.get(PUBLIC + token).status()).isEqualTo(200);

        ApiClient.Response revoked = asA("POST", INVITES + "/" + id + "/revoke", null);

        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.json().get("state").asString()).isEqualTo("REVOKED");
        assertThat(api.get(PUBLIC + token).status()).isEqualTo(404);
        assertThat(register(token, "Late", email()).status()).isEqualTo(404);
        ApiClient.Response again = asA("POST", INVITES + "/" + id + "/revoke", null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'MEMBER_INVITE_REVOKED' and entity_id = ?", Long.class, id)).isOne();
    }

    // ---- reviewing registrations ---------------------------------------------------------------------------------------

    @Test
    void approvingCreatesTheMemberAndSendsTheWelcomeEmail() {
        String address = email();
        UUID registration = pendingRegistration(sessionA, communityA, "Asha Rao", address);

        ApiClient.Response pending = asA("GET", REGISTRATIONS + "/" + registration, null);
        assertThat(pending.json().get("status").asString()).isEqualTo("PENDING");
        assertThat(pending.json().get("emailUsedByMemberNo").isNull()).isTrue();

        ApiClient.Response approved = asA("POST", REGISTRATIONS + "/" + registration + "/approve", Map.of());

        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(approved.json().get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.json().get("memberNo").asString()).matches("^[A-Z0-9]+-0001$");
        UUID memberId = UUID.fromString(approved.json().get("memberId").asString());
        Map<String, Object> member = jdbc.queryForMap("select * from members where id = ?", memberId);
        assertThat(member.get("full_name")).isEqualTo("Asha Rao");
        assertThat(member.get("email").toString()).isEqualTo(address);
        assertThat(member.get("group_label")).isEqualTo("Block A");
        assertThat(member.get("consent_email")).isEqualTo(true);
        assertThat(member.get("status")).isEqualTo("ACTIVE");
        assertThat(member.get("community_id")).isEqualTo(communityA.getId());
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where to_email = ? and template = 'member-welcome'", Long.class, address)).isOne();
        assertThat(jdbc.queryForObject("select reviewed_by from member_registrations where id = ?", UUID.class, registration)).isEqualTo(adminA.id());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'MEMBER_REGISTRATION_APPROVED' and entity_id = ?", Long.class, registration)).isOne();

        ApiClient.Response again = asA("POST", REGISTRATIONS + "/" + registration + "/approve", Map.of());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(asA("POST", REGISTRATIONS + "/" + registration + "/reject", Map.of("reason", "late")).status()).as("already decided").isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from members where community_id = ?", Long.class, communityA.getId())).isOne();
    }

    @Test
    void rejectingNeedsAReasonAndCreatesNoMember() {
        UUID registration = pendingRegistration(sessionA, communityA, "Spammer", email());

        assertThat(asA("POST", REGISTRATIONS + "/" + registration + "/reject", Map.of()).status()).isEqualTo(400);
        assertThat(asA("POST", REGISTRATIONS + "/" + registration + "/reject", Map.of("reason", " ")).status()).isEqualTo(400);
        ApiClient.Response rejected = asA("POST", REGISTRATIONS + "/" + registration + "/reject", Map.of("reason", "Not a resident"));

        assertThat(rejected.status()).as(rejected.body()).isEqualTo(200);
        assertThat(rejected.json().get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.json().get("rejectionReason").asString()).isEqualTo("Not a resident");
        assertThat(jdbc.queryForObject("select count(*) from members where community_id = ?", Long.class, communityA.getId())).isZero();
        assertThat(asA("POST", REGISTRATIONS + "/" + registration + "/approve", Map.of()).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'MEMBER_REGISTRATION_REJECTED' and entity_id = ?", Long.class, registration)).isOne();
    }

    @Test
    void approvalEnforcesThePlanMemberLimitAndLeavesTheRegistrationPending() {
        limitPlanA(1);
        data.member(communityA);
        UUID registration = pendingRegistration(sessionA, communityA, "Over Limit", email());

        ApiClient.Response response = asA("POST", REGISTRATIONS + "/" + registration + "/approve", Map.of());

        assertThat(response.status()).isEqualTo(402);
        assertThat(response.code()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        assertThat(jdbc.queryForObject("select status from member_registrations where id = ?", String.class, registration)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from members where community_id = ?", Long.class, communityA.getId())).isOne();

        limitPlanA(5);
        assertThat(asA("POST", REGISTRATIONS + "/" + registration + "/approve", Map.of()).status()).as("after an upgrade it goes through").isEqualTo(200);
    }

    @Test
    void approvalHandlesAnEmailAlreadyUsedByAMember() {
        String address = email();
        JsonNode existing = asA("POST", MemberIT.MEMBERS, Map.of("fullName", "Parent", "email", address)).json();
        UUID registration = pendingRegistration(sessionA, communityA, "Child", address);

        JsonNode view = asA("GET", REGISTRATIONS + "/" + registration, null).json();
        assertThat(view.get("emailUsedByMemberNo").asString()).as("the admin is told").isEqualTo(existing.get("memberNo").asString());

        ApiClient.Response refused = asA("POST", REGISTRATIONS + "/" + registration + "/approve", Map.of());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("DUPLICATE_EMAIL");
        assertThat(jdbc.queryForObject("select status from member_registrations where id = ?", String.class, registration)).isEqualTo("PENDING");

        ApiClient.Response allowed = asA("POST", REGISTRATIONS + "/" + registration + "/approve", Map.of("allowDuplicateEmail", true, "group", "Block Z"));
        assertThat(allowed.status()).as(allowed.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select group_label from members where id = ?", String.class, UUID.fromString(allowed.json().get("memberId").asString()))).as("the admin may change the group").isEqualTo("Block Z");
    }

    @Test
    void concurrentApprovalsCreateOneMember() throws Exception {
        UUID registration = pendingRegistration(sessionA, communityA, "Once", email());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) results.add(pool.submit(() -> asA("POST", REGISTRATIONS + "/" + registration + "/approve", Map.of()).status()));
            int ok = 0;
            int conflict = 0;
            for (var f : results) {
                int status = f.get();
                if (status == 200) ok++;
                else if (status == 409) conflict++;
            }
            assertThat(ok).isEqualTo(1);
            assertThat(conflict).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from members where community_id = ?", Long.class, communityA.getId())).isOne();
    }

    @Test
    void listsRegistrationsByStatus() {
        UUID waiting = pendingRegistration(sessionA, communityA, "Waiting", email());
        UUID approved = pendingRegistration(sessionA, communityA, "Approved", email());
        asA("POST", REGISTRATIONS + "/" + approved + "/approve", Map.of());

        assertThat(ids(asA("GET", REGISTRATIONS + "?status=PENDING", null))).containsExactly(waiting.toString());
        assertThat(ids(asA("GET", REGISTRATIONS + "?status=APPROVED", null))).containsExactly(approved.toString());
        assertThat(ids(asA("GET", REGISTRATIONS, null))).containsExactlyInAnyOrder(waiting.toString(), approved.toString());
        assertThat(asA("GET", REGISTRATIONS + "?status=BOGUS", null).status()).isEqualTo(400);
    }

    // ---- tenant isolation -------------------------------------------------------------------------------------------------

    @Test
    void invitesOfOneCommunityAreInvisibleToAnother() {
        UUID inviteB = UUID.fromString(createInvite(sessionB, Map.of()).get("invite").get("id").asString());
        createInvite(sessionA, Map.of());

        assertListHides(INVITES, inviteB);
        assertCrossTenantRead(INVITES + "/" + inviteB);
        assertCrossTenantUpdate("POST", INVITES + "/" + inviteB + "/revoke", null, () ->
                assertThat(jdbc.queryForObject("select revoked_at from member_invites where id = ?", java.sql.Timestamp.class, inviteB)).isNull());
        assertCreateCannotTargetOtherTenant(INVITES, Map.of("maxUses", 5), r -> UUID.fromString(r.json().get("invite").get("id").asString()), INVITES + "/%s");
        assertCreateCannotTargetOtherTenant(INVITES + "/email", Map.of("email", email()), r -> UUID.fromString(r.json().get("invite").get("id").asString()), INVITES + "/%s");
    }

    @Test
    void registrationsOfOneCommunityAreInvisibleToAnother() {
        UUID registrationB = pendingRegistration(sessionB, communityB, "B Applicant", email());
        pendingRegistration(sessionA, communityA, "A Applicant", email());

        assertListHides(REGISTRATIONS, registrationB);
        assertCrossTenantRead(REGISTRATIONS + "/" + registrationB);
        UUID second = pendingRegistration(sessionB, communityB, "B Second", email());
        assertCrossTenantUpdate("POST", REGISTRATIONS + "/" + registrationB + "/approve", Map.of(), () -> {
            assertThat(jdbc.queryForObject("select status from member_registrations where id = ?", String.class, registrationB)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select count(*) from members where community_id = ?", Long.class, communityA.getId())).isZero();
        });
        assertCrossTenantUpdate("POST", REGISTRATIONS + "/" + second + "/reject", Map.of("reason", "x"), () ->
                assertThat(jdbc.queryForObject("select status from member_registrations where id = ?", String.class, second)).isEqualTo("PENDING"));
        assertThat(jdbc.queryForObject("select count(*) from members where community_id = ?", Long.class, communityB.getId())).as("B's approval created B's member").isOne();
    }

    @Test
    void aSuspendedCommunityCannotCreateInvitesOrReviewRegistrations() {
        UUID registration = pendingRegistration(sessionA, communityA, "Waiting", email());
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());

        assertThat(asA("GET", INVITES, null).status()).isEqualTo(200);
        assertThat(asA("POST", INVITES, Map.of()).status()).isEqualTo(403);
        ApiClient.Response approve = asA("POST", REGISTRATIONS + "/" + registration + "/approve", Map.of());
        assertThat(approve.status()).isEqualTo(403);
        assertThat(approve.code()).isEqualTo("COMMUNITY_SUSPENDED");
    }
}

package com.amanahconnect.complaint;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.billing.AbstractFinanceIT;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.AuthTestUsers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class ComplaintIT extends AbstractFinanceIT {

    private static final String C = "/api/v1/community/complaints";

    @Autowired AuthTestUsers users;

    private JsonNode complaint(Session session, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", "Water leak " + UUID.randomUUID().toString().substring(0, 6));
        body.put("description", "Pipe in the stairwell is leaking.");
        body.putAll(extra);
        ApiClient.Response response = call(session, "POST", C, body);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json();
    }

    private JsonNode complaintA(Map<String, Object> extra) {
        return complaint(sessionA, extra);
    }

    private ApiClient.Response status(Session s, UUID id, String status, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("status", status));
        body.putAll(extra);
        return call(s, "POST", C + "/" + id + "/status", body);
    }

    private List<String> ids(ApiClient.Response response) {
        List<String> out = new ArrayList<>();
        response.json().get("items").forEach(i -> out.add(i.get("id").asString()));
        return out;
    }

    // ---- create / read / edit ---------------------------------------------------------------------------------------------

    @Test
    void logsAComplaintWithDefaults() {
        JsonNode created = complaintA(Map.of());

        assertThat(created.get("status").asString()).isEqualTo("OPEN");
        assertThat(created.get("priority").asString()).isEqualTo("MEDIUM");
        assertThat(created.get("memberId").isNull()).isTrue();
        assertThat(created.get("ageDays").asLong()).isZero();
        assertThat(created.get("slaTargetDays").asInt()).isEqualTo(7);
        assertThat(created.get("slaBreached").asBoolean()).isFalse();
        assertThat(created.get("resolvedAt").isNull()).isTrue();
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'COMPLAINT_CREATED'", communityA.getId())).isEqualTo(1);
    }

    @Test
    void attachesAMemberAndAnAssignee() {
        UUID member = memberA("Asha Rao");
        AuthTestUsers.TestUser other = users.extraAdminOf(communityA);

        JsonNode created = complaintA(Map.of("memberId", member.toString(), "priority", "HIGH", "category", "  Plumbing ", "assignedTo", other.id().toString()));

        assertThat(created.get("memberName").asString()).isEqualTo("Asha Rao");
        assertThat(created.get("category").asString()).isEqualTo("Plumbing");
        assertThat(created.get("assignedTo").asString()).isEqualTo(other.id().toString());
        assertThat(created.get("assignedToName").asString()).isNotBlank();
    }

    @Test
    void rejectsBadInput() {
        assertThat(call(sessionA, "POST", C, Map.of("subject", "", "description", "x")).status()).isEqualTo(400);
        assertThat(call(sessionA, "POST", C, Map.of("subject", "x", "description", " ")).status()).isEqualTo(400);
        assertThat(call(sessionA, "POST", C, Map.of("subject", "x", "description", "y", "priority", "SEVERE")).status()).isEqualTo(400);
        assertThat(call(sessionA, "POST", C, Map.of("subject", "\u0000\u0007", "description", "y")).status()).as("control characters only").isEqualTo(400);
        assertThat(call(sessionA, "POST", C, Map.of("subject", "x".repeat(201), "description", "y")).status()).isEqualTo(400);
    }

    @Test
    void anAssigneeMustBeAnAdminOfThisCommunity() {
        AuthTestUsers.TestUser otherTenantAdmin = users.extraAdminOf(communityB);
        ApiClient.Response foreign = call(sessionA, "POST", C, Map.of("subject", "x", "description", "y", "assignedTo", otherTenantAdmin.id().toString()));
        ApiClient.Response nobody = call(sessionA, "POST", C, Map.of("subject", "x", "description", "y", "assignedTo", UUID.randomUUID().toString()));

        assertThat(foreign.status()).isEqualTo(400);
        assertThat(foreign.json().get("errors")).isEqualTo(nobody.json().get("errors"));
    }

    @Test
    void editsAndUnassigns() {
        AuthTestUsers.TestUser other = users.extraAdminOf(communityA);
        UUID id = id(complaintA(Map.of("assignedTo", other.id().toString())));

        ApiClient.Response edited = call(sessionA, "PATCH", C + "/" + id, Map.of("subject", "New subject", "priority", "URGENT", "unassign", true));

        assertThat(edited.status()).as(edited.body()).isEqualTo(200);
        assertThat(edited.json().get("subject").asString()).isEqualTo("New subject");
        assertThat(edited.json().get("priority").asString()).isEqualTo("URGENT");
        assertThat(edited.json().get("assignedTo").isNull()).isTrue();
        assertThat(edited.json().get("slaTargetDays").asInt()).isEqualTo(1);
        assertThat(call(sessionA, "PATCH", C + "/" + id, Map.of("assignedTo", other.id().toString(), "unassign", true)).status()).isEqualTo(400);
    }

    // ---- status -----------------------------------------------------------------------------------------------------------

    @Test
    void resolvingStampsResolvedAtAndReopeningClearsIt() {
        UUID id = id(complaintA(Map.of()));

        JsonNode resolved = status(sessionA, id, "RESOLVED", Map.of()).json();
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolvedAt").isNull()).isFalse();

        JsonNode reopened = status(sessionA, id, "IN_PROGRESS", Map.of()).json();
        assertThat(reopened.get("resolvedAt").isNull()).isTrue();
        assertThat(reopened.get("closedAt").isNull()).isTrue();
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'COMPLAINT_STATUS_CHANGED'", communityA.getId())).isEqualTo(2);
    }

    @Test
    void closedComplaintsAreReadOnlyUntilReopened() {
        UUID id = id(complaintA(Map.of()));
        JsonNode closed = status(sessionA, id, "CLOSED", Map.of()).json();
        assertThat(closed.get("closedAt").isNull()).isFalse();

        assertThat(call(sessionA, "PATCH", C + "/" + id, Map.of("subject", "nope")).status()).isEqualTo(409);
        ApiClient.Response comment = call(sessionA, "POST", C + "/" + id + "/comments", Map.of("body", "late", "visibility", "INTERNAL"));
        assertThat(comment.status()).isEqualTo(409);
        assertThat(comment.code()).isEqualTo("COMPLAINT_CLOSED");

        assertThat(status(sessionA, id, "OPEN", Map.of()).status()).isEqualTo(200);
        assertThat(call(sessionA, "POST", C + "/" + id + "/comments", Map.of("body", "back", "visibility", "INTERNAL")).status()).isEqualTo(201);
    }

    @Test
    void aResolvedComplaintKeepsItsResolvedTimeWhenClosed() {
        UUID id = id(complaintA(Map.of()));
        String resolvedAt = status(sessionA, id, "RESOLVED", Map.of()).json().get("resolvedAt").asString();

        JsonNode closed = status(sessionA, id, "CLOSED", Map.of()).json();

        assertThat(closed.get("resolvedAt").asString()).isEqualTo(resolvedAt);
    }

    @Test
    void settingTheSameStatusIsANoOp() {
        UUID id = id(complaintA(Map.of()));
        assertThat(status(sessionA, id, "OPEN", Map.of()).status()).isEqualTo(200);
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'COMPLAINT_STATUS_CHANGED'", communityA.getId())).isZero();
    }

    // ---- comments and emails ------------------------------------------------------------------------------------------------

    @Test
    void keepsInternalNotesAndMemberUpdatesApart() {
        String address = email();
        UUID member = member(sessionA, "Notified", address, true);
        UUID id = id(complaintA(Map.of("memberId", member.toString())));

        ApiClient.Response internal = call(sessionA, "POST", C + "/" + id + "/comments", Map.of("body", "Plumber is overcharging, do not tell them", "visibility", "INTERNAL"));
        ApiClient.Response visible = call(sessionA, "POST", C + "/" + id + "/comments", Map.of("body", "A plumber visits on Friday", "visibility", "MEMBER", "notifyMember", true));

        assertThat(internal.status()).isEqualTo(201);
        assertThat(internal.json().get("emailOutcome").isNull()).isTrue();
        assertThat(visible.json().get("emailOutcome").asString()).isEqualTo("QUEUED");
        List<Map<String, Object>> mails = outbox(address, "complaint-update");
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).get("payload").toString()).contains("A plumber visits on Friday").doesNotContain("overcharging");
        JsonNode thread = call(sessionA, "GET", C + "/" + id + "/comments", null).json();
        assertThat(thread).hasSize(2);
        assertThat(thread.get(0).get("visibility").asString()).isEqualTo("INTERNAL");
        assertThat(thread.get(1).get("visibility").asString()).isEqualTo("MEMBER");
        assertThat(call(sessionA, "GET", C + "/" + id, null).json().get("commentCount").asInt()).isEqualTo(2);
    }

    @Test
    void anInternalNoteIsNeverEmailed() {
        UUID member = member(sessionA, "Quiet", email(), true);
        UUID id = id(complaintA(Map.of("memberId", member.toString())));
        assertThat(call(sessionA, "POST", C + "/" + id + "/comments", Map.of("body", "secret", "visibility", "INTERNAL", "notifyMember", true)).status()).isEqualTo(400);
        assertThat(count("select count(*) from email_outbox where community_id = ? and template = 'complaint-update'", communityA.getId())).isZero();
    }

    @Test
    void notifyNeedsAMember() {
        UUID id = id(complaintA(Map.of()));
        assertThat(call(sessionA, "POST", C + "/" + id + "/comments", Map.of("body", "x", "visibility", "MEMBER", "notifyMember", true)).status()).isEqualTo(400);
        assertThat(status(sessionA, id, "RESOLVED", Map.of("notifyMember", true)).status()).isEqualTo(400);
        assertThat(call(sessionA, "GET", C + "/" + id, null).json().get("status").asString()).as("nothing changed").isEqualTo("OPEN");
    }

    @Test
    void statusChangeEmailsOnlyWhenAskedAndKeepsTheNote() {
        String address = email();
        UUID member = member(sessionA, "Status", address, true);
        UUID id = id(complaintA(Map.of("memberId", member.toString())));

        status(sessionA, id, "IN_PROGRESS", Map.of());
        assertThat(outbox(address, "complaint-update")).as("not asked, not sent").isEmpty();

        ApiClient.Response resolved = status(sessionA, id, "RESOLVED", Map.of("notifyMember", true, "note", "Fixed the pipe"));

        assertThat(resolved.json().get("emailOutcome").asString()).isEqualTo("QUEUED");
        assertThat(outbox(address, "complaint-update")).hasSize(1);
        assertThat(outbox(address, "complaint-update").get(0).get("payload").toString()).contains("RESOLVED", "Fixed the pipe");
        JsonNode thread = call(sessionA, "GET", C + "/" + id + "/comments", null).json();
        assertThat(thread.get(0).get("body").asString()).isEqualTo("Fixed the pipe");
        assertThat(thread.get(0).get("visibility").asString()).isEqualTo("MEMBER");
    }

    @Test
    void aMemberWithoutConsentOrAddressIsNotEmailed() {
        UUID noConsent = member(sessionA, "No Consent", email(), false);
        UUID noAddress = member(sessionA, "No Address", null, true);
        UUID a = id(complaintA(Map.of("memberId", noConsent.toString())));
        UUID b = id(complaintA(Map.of("memberId", noAddress.toString())));

        assertThat(status(sessionA, a, "RESOLVED", Map.of("notifyMember", true)).json().get("emailOutcome").asString()).isEqualTo("NO_CONSENT");
        assertThat(status(sessionA, b, "RESOLVED", Map.of("notifyMember", true)).json().get("emailOutcome").asString()).isEqualTo("NO_ADDRESS");
        assertThat(count("select count(*) from email_outbox where community_id = ? and template = 'complaint-update'", communityA.getId())).isZero();
    }

    @Test
    void theEmailQuotaStopsTheEmailButNotTheUpdate() {
        String address = email();
        UUID member = member(sessionA, "Over Quota", address, true);
        UUID id = id(complaintA(Map.of("memberId", member.toString())));
        long queued = count("select count(*) from email_outbox where community_id = ?", communityA.getId());
        Plan limited = data.customPlan("No emails left", Map.of("emails_per_month", queued), Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", limited.getId(), communityA.getId());

        ApiClient.Response response = status(sessionA, id, "RESOLVED", Map.of("notifyMember", true));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("status").asString()).isEqualTo("RESOLVED");
        assertThat(response.json().get("emailOutcome").asString()).isEqualTo("QUOTA");
        assertThat(outbox(address, "complaint-update")).isEmpty();
    }

    // ---- list, filters, counts, SLA -----------------------------------------------------------------------------------------

    @Test
    void filtersAndSearches() {
        UUID asha = memberA("Asha Verma");
        UUID low = id(complaintA(Map.of("subject", "Lift noise", "priority", "LOW", "category", "Lift", "memberId", asha.toString())));
        UUID urgent = id(complaintA(Map.of("subject", "Gas smell", "priority", "URGENT", "category", "Safety")));
        UUID done = id(complaintA(Map.of("subject", "Broken light", "priority", "MEDIUM", "category", "Lift")));
        status(sessionA, done, "RESOLVED", Map.of());

        assertThat(ids(call(sessionA, "GET", C, null))).containsExactlyInAnyOrder(low.toString(), urgent.toString(), done.toString());
        assertThat(ids(call(sessionA, "GET", C + "?status=OPEN", null))).containsExactlyInAnyOrder(low.toString(), urgent.toString());
        assertThat(ids(call(sessionA, "GET", C + "?status=OPEN&status=RESOLVED", null))).hasSize(3);
        assertThat(ids(call(sessionA, "GET", C + "?priority=URGENT", null))).containsExactly(urgent.toString());
        assertThat(ids(call(sessionA, "GET", C + "?category=lift", null))).containsExactlyInAnyOrder(low.toString(), done.toString());
        assertThat(ids(call(sessionA, "GET", C + "?memberId=" + asha, null))).containsExactly(low.toString());
        assertThat(ids(call(sessionA, "GET", C + "?q=asha", null))).as("member name").containsExactly(low.toString());
        assertThat(ids(call(sessionA, "GET", C + "?q=GAS", null))).as("subject, any case").containsExactly(urgent.toString());
        assertThat(ids(call(sessionA, "GET", C + "?q=%25", null))).as("a percent sign is not a wildcard").isEmpty();
        assertThat(ids(call(sessionA, "GET", C + "?unassigned=true", null))).hasSize(3);
        assertThat(ids(call(sessionA, "GET", C + "?mine=true", null))).isEmpty();
        assertThat(ids(call(sessionA, "GET", C + "?createdFrom=" + TODAY + "&createdTo=" + TODAY, null))).hasSize(3);
        assertThat(ids(call(sessionA, "GET", C + "?createdTo=" + TODAY.minusDays(1), null))).isEmpty();
        assertThat(ids(call(sessionA, "GET", C + "?sort=priority,desc", null)).get(0)).as("urgent first").isEqualTo(urgent.toString());
        assertThat(call(sessionA, "GET", C + "?sort=description", null).status()).as("not on the sort whitelist").isEqualTo(400);
        assertThat(call(sessionA, "GET", C + "?status=NOPE", null).status()).isEqualTo(400);
    }

    @Test
    void mineAndAssignedToFilters() {
        UUID mine = id(complaintA(Map.of("assignedTo", adminA.id().toString())));
        AuthTestUsers.TestUser other = users.extraAdminOf(communityA);
        UUID theirs = id(complaintA(Map.of("assignedTo", other.id().toString())));

        assertThat(ids(call(sessionA, "GET", C + "?mine=true", null))).containsExactly(mine.toString());
        assertThat(ids(call(sessionA, "GET", C + "?assignedTo=" + other.id(), null))).containsExactly(theirs.toString());
        assertThat(call(sessionA, "GET", C + "/counts", null).json().get("assignedToMe").asLong()).isEqualTo(1);
    }

    @Test
    void ageAndTheSlaFlagFollowThePriority() {
        UUID urgent = id(complaintA(Map.of("priority", "URGENT")));
        UUID low = id(complaintA(Map.of("priority", "LOW")));
        UUID resolved = id(complaintA(Map.of("priority", "URGENT")));
        status(sessionA, resolved, "RESOLVED", Map.of());
        jdbc.update("update complaints set created_at = now() - interval '3 days' where id in (?, ?, ?)", urgent, low, resolved);
        jdbc.update("update complaints set resolved_at = created_at + interval '2 days' where id = ?", resolved);

        JsonNode u = call(sessionA, "GET", C + "/" + urgent, null).json();
        JsonNode l = call(sessionA, "GET", C + "/" + low, null).json();
        JsonNode r = call(sessionA, "GET", C + "/" + resolved, null).json();

        assertThat(u.get("ageDays").asLong()).isEqualTo(3);
        assertThat(u.get("slaBreached").asBoolean()).as("urgent is due in 1 day").isTrue();
        assertThat(l.get("slaBreached").asBoolean()).as("low has 14 days").isFalse();
        assertThat(r.get("slaBreached").asBoolean()).as("resolved ones are not alerts").isFalse();
        assertThat(r.get("ageDays").asLong()).as("age stops at resolution").isEqualTo(2);
        assertThat(ids(call(sessionA, "GET", C + "?slaBreached=true", null))).containsExactly(urgent.toString());
    }

    @Test
    void countsForTheDashboard() {
        complaintA(Map.of("priority", "URGENT", "category", "Safety"));
        UUID progress = id(complaintA(Map.of("category", "Safety")));
        UUID resolved = id(complaintA(Map.of()));
        UUID closed = id(complaintA(Map.of()));
        status(sessionA, progress, "IN_PROGRESS", Map.of());
        status(sessionA, resolved, "RESOLVED", Map.of());
        status(sessionA, closed, "CLOSED", Map.of());
        complaint(sessionB, Map.of());

        JsonNode counts = call(sessionA, "GET", C + "/counts", null).json();

        assertThat(counts.get("total").asLong()).isEqualTo(4);
        assertThat(counts.get("open").asLong()).isEqualTo(1);
        assertThat(counts.get("inProgress").asLong()).isEqualTo(1);
        assertThat(counts.get("resolved").asLong()).isEqualTo(1);
        assertThat(counts.get("closed").asLong()).isEqualTo(1);
        assertThat(counts.get("active").asLong()).isEqualTo(2);
        assertThat(counts.get("urgentActive").asLong()).isEqualTo(1);
        assertThat(counts.get("unassignedActive").asLong()).isEqualTo(2);
        assertThat(counts.get("byCategory").get("Safety").asLong()).isEqualTo(2);
        assertThat(counts.get("byPriority").get("URGENT").asLong()).isEqualTo(1);
        assertThat(counts.get("byPriority").get("LOW").asLong()).isZero();
    }

    // ---- tenant isolation ---------------------------------------------------------------------------------------------------

    @Test
    void isolationOfEveryEndpoint() {
        UUID b = id(complaint(sessionB, Map.of()));
        String before = jdbc.queryForObject("select subject || status || priority from complaints where id = ?", String.class, b);
        Runnable unchanged = () -> assertThat(jdbc.queryForObject("select subject || status || priority from complaints where id = ?", String.class, b)).isEqualTo(before);

        assertListHides(C, b);
        assertCrossTenantRead(C + "/" + b);
        assertCrossTenantRead(C + "/" + b + "/comments");
        assertCrossTenantUpdate("PATCH", C + "/" + b, Map.of("subject", "Hijacked"), unchanged);
        UUID b2 = id(complaint(sessionB, Map.of()));
        long commentsBefore = count("select count(*) from complaint_comments where community_id = ?", communityB.getId());
        assertCrossTenantUpdate("POST", C + "/" + b2 + "/comments", Map.of("body", "x", "visibility", "INTERNAL"),
                () -> assertThat(count("select count(*) from complaint_comments where community_id = ?", communityB.getId())).isEqualTo(commentsBefore));
        UUID b3 = id(complaint(sessionB, Map.of()));
        String before3 = jdbc.queryForObject("select status from complaints where id = ?", String.class, b3);
        assertCrossTenantUpdate("POST", C + "/" + b3 + "/status", Map.of("status", "RESOLVED"),
                () -> assertThat(jdbc.queryForObject("select status from complaints where id = ?", String.class, b3)).isEqualTo(before3));
        assertCreateCannotTargetOtherTenant(C, new LinkedHashMap<>(Map.of("subject", "Mine", "description", "x")), r -> id(r.json()), C + "/%s");
        assertTenantSingleton("GET", C + "/counts", null, () -> call(sessionB, "GET", C + "/counts", null).body());
        assertThat(call(sessionA, "GET", C + "/counts", null).json().get("total").asLong()).as("B's complaints are not counted").isEqualTo(1);
    }

    @Test
    void aComplaintCannotPointAtAnotherCommunitysMember() {
        UUID memberOfB = member(sessionB, "B Person", email(), true);
        ApiClient.Response foreign = call(sessionA, "POST", C, Map.of("subject", "x", "description", "y", "memberId", memberOfB.toString()));
        ApiClient.Response missing = call(sessionA, "POST", C, Map.of("subject", "x", "description", "y", "memberId", UUID.randomUUID().toString()));

        assertThat(foreign.status()).isEqualTo(404);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(count("select count(*) from complaints where community_id = ?", communityA.getId())).isZero();
        UUID own = id(complaintA(Map.of()));
        assertThat(call(sessionA, "PATCH", C + "/" + own, Map.of("memberId", memberOfB.toString())).status()).isEqualTo(404);
    }
}

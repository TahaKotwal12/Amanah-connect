package com.amanahconnect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.Community;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.AbstractAdminIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.TestData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminCommunityIT extends AbstractAdminIT {

    private static final String COMMUNITIES = ADMIN + "/communities";

    private Map<String, Object> row(String sql, Object... args) {
        return jdbc.queryForMap(sql, args);
    }

    // ---- create ---------------------------------------------------------------------------------

    @Test
    void createsTheCommunityItsOwnerTheLinkTheInvitationAndTheAuditRecord() {
        String ownerEmail = "owner-" + TestData.unique() + "@example.test";
        Map<String, Object> body = newCommunityBody(starter(), "Garden Society", ownerEmail);

        ApiClient.Response response = adminPost(COMMUNITIES, body);

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        var json = response.json();
        UUID id = UUID.fromString(json.get("id").asString());
        assertThat(json.get("status").asString()).isEqualTo("PENDING");
        assertThat(json.get("slug").asString()).isEqualTo("garden-society");
        assertThat(json.get("plan").get("code").asString()).isEqualTo("STARTER");
        assertThat(json.get("owner").get("email").asString()).isEqualTo(ownerEmail);
        assertThat(json.get("owner").get("status").asString()).isEqualTo("INVITED");
        assertThat(json.get("country").asString()).isEqualTo("IN");
        assertThat(json.get("financialYearStartMonth").asInt()).isEqualTo(4);

        Map<String, Object> community = row("select * from communities where id = ?", id);
        assertThat(community.get("status")).isEqualTo("PENDING");
        Map<String, Object> owner = row("select * from users where email = ?", ownerEmail);
        assertThat(owner.get("status")).isEqualTo("INVITED");
        assertThat(owner.get("role")).isEqualTo("COMMUNITY_ADMIN");
        assertThat(owner.get("password_hash")).isNull();
        assertThat(community.get("owner_user_id")).isEqualTo(owner.get("id"));
        assertThat(row("select role from community_users where community_id = ?", id).get("role")).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("select count(*) from ledger_categories where community_id = ?", Long.class, id)).as("default categories were copied").isPositive();

        List<Map<String, Object>> invitation = emailsTo(ownerEmail, "invitation");
        assertThat(invitation).hasSize(1);
        assertThat(invitation.get(0).get("community_id")).as("platform email, not on the community's quota").isNull();
        assertThat(invitation.get(0).get("payload").toString()).contains("/accept-invite?token=");
        assertThat(response.body()).as("the token never appears in the API response").doesNotContain("token");

        List<Map<String, Object>> created = jdbc.queryForList("select * from audit_logs where action = 'COMMUNITY_CREATED' and community_id = ?", id);
        assertThat(created).hasSize(1);
        assertThat(created.get(0).get("actor_user_id")).isEqualTo(superAdmin.id());
        assertThat(created.get(0).get("after").toString()).contains("Garden Society");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'INVITATION_CREATED' and entity_id = ?", Long.class, owner.get("id"))).isOne();
    }

    @Test
    void generatesUniqueSlugsAndRefusesATakenOne() {
        createCommunityViaApi("Same Name");

        ApiClient.Response second = adminPost(COMMUNITIES, newCommunityBody(starter(), "Same Name", "o2-" + TestData.unique() + "@example.test"));
        assertThat(second.json().get("slug").asString()).isEqualTo("same-name-2");

        Map<String, Object> explicit = newCommunityBody(starter(), "Other", "o3-" + TestData.unique() + "@example.test");
        explicit.put("slug", "same-name");
        ApiClient.Response taken = adminPost(COMMUNITIES, explicit);
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.code()).isEqualTo("SLUG_TAKEN");

        explicit.put("slug", "Bad Slug!");
        assertThat(adminPost(COMMUNITIES, explicit).status()).isEqualTo(400);
    }

    @Test
    void refusesAnOwnerEmailThatAlreadyHasAnAccountIgnoringCase() {
        var existing = users.communityAdmin();

        ApiClient.Response response = adminPost(COMMUNITIES, newCommunityBody(starter(), "Dup Owner", existing.email().toUpperCase()));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("EMAIL_ALREADY_REGISTERED");
        assertThat(jdbc.queryForObject("select count(*) from communities where name = 'Dup Owner'", Long.class)).as("nothing was created").isZero();
    }

    @Test
    void refusesAnInactiveOrUnknownPlan() {
        Plan retired = data.customPlan("Retired", Map.of(), Map.of());
        jdbc.update("update plans set active = false where id = ?", retired.getId());

        ApiClient.Response inactive = adminPost(COMMUNITIES, newCommunityBody(retired, "On Retired", "o-" + TestData.unique() + "@example.test"));
        Map<String, Object> unknown = newCommunityBody(starter(), "Unknown Plan", "u-" + TestData.unique() + "@example.test");
        unknown.put("planId", UUID.randomUUID().toString());

        assertThat(inactive.status()).isEqualTo(409);
        assertThat(inactive.code()).isEqualTo("PLAN_INACTIVE");
        assertThat(adminPost(COMMUNITIES, unknown).status()).isEqualTo(400);
    }

    @Test
    void validatesTheRequest() {
        assertThat(adminPost(COMMUNITIES, Map.of()).code()).isEqualTo("VALIDATION_FAILED");

        Map<String, Object> bad = newCommunityBody(starter(), "x".repeat(201), "not-an-email");
        bad.put("contactPhone", "call me maybe");
        bad.put("dateOfEstablishment", "2999-01-01");
        ApiClient.Response response = adminPost(COMMUNITIES, bad);

        assertThat(response.status()).isEqualTo(400);
        String errors = response.json().get("errors").toString();
        assertThat(errors).contains("name").contains("ownerEmail").contains("contactPhone").contains("dateOfEstablishment");
    }

    // ---- activation -----------------------------------------------------------------------------

    @Test
    void theCommunityBecomesActiveWhenTheOwnerAcceptsTheInvitation() {
        String ownerEmail = "owner-" + TestData.unique() + "@example.test";
        UUID id = UUID.fromString(adminPost(COMMUNITIES, newCommunityBody(starter(), "Accepts Soon", ownerEmail)).json().get("id").asString());
        String token = tokenFromEmail("invitation", ownerEmail);

        ApiClient.Response accept = api.post("/api/v1/auth/accept-invite", Map.of("token", token, "newPassword", "Correct-Horse-9-Staple"));

        assertThat(accept.status()).isEqualTo(200);
        Map<String, Object> community = row("select * from communities where id = ?", id);
        assertThat(community.get("status")).isEqualTo("ACTIVE");
        assertThat(community.get("status_reason")).isEqualTo("The owner accepted the invitation.");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'COMMUNITY_ACTIVATED' and community_id = ?", Long.class, id)).isOne();
        assertThat(api.post("/api/v1/auth/login", Map.of("email", ownerEmail, "password", "Correct-Horse-9-Staple")).status()).isEqualTo(200);
    }

    @Test
    void aSuperAdminCanActivateAPendingCommunityAndTheOwnerIsEmailed() {
        String ownerEmail = "owner-" + TestData.unique() + "@example.test";
        UUID id = UUID.fromString(adminPost(COMMUNITIES, newCommunityBody(starter(), "Manual Activate", ownerEmail)).json().get("id").asString());

        ApiClient.Response response = adminPost(COMMUNITIES + "/" + id + "/activate", Map.of("reason", "Verified by phone"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(response.json().get("statusReason").asString()).isEqualTo("Verified by phone");
        assertThat(emailsTo(ownerEmail, "community-activated")).hasSize(1);
        assertThat(adminPost(COMMUNITIES + "/" + id + "/activate", Map.of()).code()).as("already active").isEqualTo("INVALID_STATE_TRANSITION");
    }

    // ---- suspension -----------------------------------------------------------------------------

    @Test
    void suspendingNeedsAReasonEmailsTheOwnerAuditsAndMakesTheCommunityReadOnly() {
        Community community = data.community();
        var owner = users.communityAdminOf(community);
        jdbc.update("update communities set owner_user_id = ? where id = ?", owner.id(), community.getId());
        Session ownerSession = loginOk(owner);
        String noop = "/api/v1/community/test-support/noop";
        assertThat(api.post(noop, null, "Authorization", ownerSession.bearer()).status()).as("writable before").isEqualTo(204);

        assertThat(adminPost(COMMUNITIES + "/" + community.getId() + "/suspend", Map.of()).status()).as("a reason is required").isEqualTo(400);
        ApiClient.Response response = adminPost(COMMUNITIES + "/" + community.getId() + "/suspend", Map.of("reason", "Unpaid fees"));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(response.json().get("statusReason").asString()).isEqualTo("Unpaid fees");
        assertThat(emailsTo(owner.email(), "community-suspended")).hasSize(1);
        assertThat(emailsTo(owner.email(), "community-suspended").get(0).get("payload").toString()).contains("Unpaid fees");
        Map<String, Object> audit = jdbc.queryForMap("select * from audit_logs where action = 'COMMUNITY_SUSPENDED' and community_id = ?", community.getId());
        assertThat(audit.get("actor_user_id")).isEqualTo(superAdmin.id());
        assertThat(audit.get("before").toString()).contains("ACTIVE");
        assertThat(audit.get("after").toString()).contains("SUSPENDED").contains("Unpaid fees");

        assertThat(api.post(noop, null, "Authorization", ownerSession.bearer()).code()).as("writes are now refused").isEqualTo("COMMUNITY_SUSPENDED");
        assertThat(api.get("/api/v1/community/test-support/whoami", "Authorization", ownerSession.bearer()).status()).as("reads still work").isEqualTo(200);
        assertThat(adminPost(COMMUNITIES + "/" + community.getId() + "/suspend", Map.of("reason", "again")).code()).isEqualTo("INVALID_STATE_TRANSITION");

        assertThat(adminPost(COMMUNITIES + "/" + community.getId() + "/activate", Map.of("reason", "Paid")).status()).isEqualTo(200);
        assertThat(api.post(noop, null, "Authorization", ownerSession.bearer()).status()).as("writable again").isEqualTo(204);
        assertThat(emailsTo(owner.email(), "community-activated")).hasSize(1);
    }

    @Test
    void archivingIsSoftAndReversible() {
        Community community = data.community();
        var member = data.member(community);

        ApiClient.Response archived = adminPost(COMMUNITIES + "/" + community.getId() + "/archive", Map.of("reason", "Closed down"));

        assertThat(archived.json().get("status").asString()).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("select count(*) from members where id = ?", Long.class, member.getId())).as("nothing is deleted").isOne();
        assertThat(adminPost(COMMUNITIES + "/" + community.getId() + "/archive", Map.of("reason", "again")).code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(adminPost(COMMUNITIES + "/" + community.getId() + "/activate", Map.of()).json().get("status").asString()).isEqualTo("ACTIVE");
    }

    // ---- list -----------------------------------------------------------------------------------

    @Test
    void listsWithSearchFiltersSortPaginationMemberCountsAndSubscriptionExpiry() {
        String tag = TestData.unique();
        Plan gold = data.customPlan("Gold " + tag, Map.of(), Map.of());
        Community alpha = communityNamed("Alpha " + tag, starter(), "ACTIVE");
        Community bravo = communityNamed("Bravo " + tag, gold, "SUSPENDED");
        Community charlie = communityNamed("Charlie " + tag, starter(), "ACTIVE");
        data.member(alpha);
        data.member(alpha);
        data.member(bravo);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        insertSubscription(alpha, starter(), today.minusDays(300), today.plusDays(3), "ACTIVE", "ref-a-" + tag, "1000.00");
        insertSubscription(bravo, gold, today.minusDays(400), today.minusDays(5), "EXPIRED", "ref-b-" + tag, "2000.00");

        var all = adminGet(COMMUNITIES + "?q=" + tag + "&sort=name").json();
        assertThat(all.get("total").asInt()).isEqualTo(3);
        List<String> names = all.get("items").valueStream().map(n -> n.get("name").asString()).toList();
        assertThat(names).containsExactly("Alpha " + tag, "Bravo " + tag, "Charlie " + tag);
        var items = all.get("items");
        assertThat(items.get(0).get("memberCount").asInt()).isEqualTo(2);
        assertThat(items.get(0).get("subscriptionStatus").asString()).isEqualTo("EXPIRING");
        assertThat(items.get(0).get("subscriptionEndsOn").asString()).isEqualTo(today.plusDays(3).toString());
        assertThat(items.get(1).get("subscriptionStatus").asString()).isEqualTo("EXPIRED");
        assertThat(items.get(2).get("subscriptionStatus").asString()).as("no subscription at all").isEqualTo("NONE");
        assertThat(items.get(0).get("plan").get("code").asString()).isEqualTo("STARTER");

        assertThat(adminGet(COMMUNITIES + "?q=" + tag + "&status=SUSPENDED").json().get("total").asInt()).isEqualTo(1);
        assertThat(adminGet(COMMUNITIES + "?q=" + tag + "&plan=" + gold.getCode().toLowerCase()).json().get("items").get(0).get("name").asString()).isEqualTo("Bravo " + tag);
        assertThat(names(adminGet(COMMUNITIES + "?q=" + tag + "&sort=memberCount,desc"))).startsWith("Alpha " + tag);
        assertThat(names(adminGet(COMMUNITIES + "?q=" + tag + "&sort=name,desc"))).startsWith("Charlie " + tag);

        var firstPage = adminGet(COMMUNITIES + "?q=" + tag + "&sort=name&size=2&page=0").json();
        var secondPage = adminGet(COMMUNITIES + "?q=" + tag + "&sort=name&size=2&page=1").json();
        assertThat(firstPage.get("items").size()).isEqualTo(2);
        assertThat(secondPage.get("items").size()).isOne();
        assertThat(firstPage.get("total").asInt()).isEqualTo(3);
        assertThat(charlie.getId()).isNotNull();
    }

    @Test
    void searchMatchesSlugOwnerEmailAndContactEmailAndTreatsWildcardsLiterally() {
        String tag = TestData.unique();
        var owner = users.communityAdmin();
        Community community = communityNamed("Searchable " + tag, starter(), "ACTIVE");
        jdbc.update("update communities set owner_user_id = ?, contact_email = ? where id = ?", owner.id(), "contact-" + tag + "@example.test", community.getId());

        assertThat(totalFor("q=" + community.getSlug())).isEqualTo(1);
        assertThat(totalFor("q=" + owner.email())).as("owner email").isEqualTo(1);
        assertThat(totalFor("q=contact-" + tag)).as("contact email").isEqualTo(1);
        assertThat(totalFor("q=%25")).as("a literal percent sign matches nothing, not everything").isZero();
        assertThat(totalFor("q=_")).isZero();
        assertThat(adminGet(COMMUNITIES + "?sort=password").status()).as("only whitelisted sort fields").isEqualTo(400);
        assertThat(adminGet(COMMUNITIES + "?status=BOGUS").status()).isEqualTo(400);
        assertThat(adminGet(COMMUNITIES + "?size=101").status()).isEqualTo(400);
    }

    private int totalFor(String query) {
        return adminGet(COMMUNITIES + "?" + query).json().get("total").asInt();
    }

    private List<String> names(ApiClient.Response response) {
        return response.json().get("items").valueStream().map(n -> n.get("name").asString()).toList();
    }

    private Community communityNamed(String name, Plan plan, String status) {
        Community community = data.communityOn(plan);
        jdbc.update("update communities set name = ?, status = ? where id = ?", name, status, community.getId());
        return community;
    }

    // ---- detail and update ----------------------------------------------------------------------

    @Test
    void detailShowsTheCommunityAndUpdateChangesOnlyWhatWasSent() {
        UUID id = createCommunityViaApi("Before Name");
        var detail = adminGet(COMMUNITIES + "/" + id).json();
        long version = detail.get("version").asLong();

        ApiClient.Response response = admin("PATCH", COMMUNITIES + "/" + id, Map.of("name", "After Name", "city", "Mumbai", "version", version));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().get("name").asString()).isEqualTo("After Name");
        assertThat(response.json().get("city").asString()).isEqualTo("Mumbai");
        assertThat(response.json().get("state").asString()).as("untouched").isEqualTo("Maharashtra");
        assertThat(response.json().get("slug").asString()).as("slug never changes").isEqualTo(detail.get("slug").asString());
        assertThat(response.json().get("version").asLong()).isGreaterThan(version);
        Map<String, Object> audit = jdbc.queryForMap("select * from audit_logs where action = 'COMMUNITY_UPDATED' and community_id = ?", id);
        assertThat(audit.get("before").toString()).contains("Before Name");
        assertThat(audit.get("after").toString()).contains("After Name");
    }

    @Test
    void aStaleVersionIsRefusedWith409() {
        UUID id = createCommunityViaApi("Versioned");
        long version = adminGet(COMMUNITIES + "/" + id).json().get("version").asLong();
        admin("PATCH", COMMUNITIES + "/" + id, Map.of("city", "First Edit"));

        ApiClient.Response stale = admin("PATCH", COMMUNITIES + "/" + id, Map.of("city", "Second Edit", "version", version));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(adminGet(COMMUNITIES + "/" + id).json().get("city").asString()).isEqualTo("First Edit");
    }

    @Test
    void aSuperAdminCanRequireTwoFactorForACommunity() {
        Community community = data.community();
        var owner = users.communityAdminOf(community);
        assertThat(login(owner).json().get("mfaSetupRequired").asBoolean()).isFalse();

        ApiClient.Response response = admin("PATCH", COMMUNITIES + "/" + community.getId(), Map.of("require2fa", true));

        assertThat(response.json().get("require2fa").asBoolean()).isTrue();
        assertThat(login(owner).json().get("mfaSetupRequired").asBoolean()).as("takes effect at the next login").isTrue();
    }

    @Test
    void changingToAnInactivePlanIsRefusedButKeepingTheCurrentOneIsFine() {
        Plan retired = data.customPlan("Old Plan", Map.of(), Map.of());
        Community community = data.communityOn(retired);
        jdbc.update("update plans set active = false where id = ?", retired.getId());

        assertThat(admin("PATCH", COMMUNITIES + "/" + community.getId(), Map.of("planId", retired.getId().toString(), "city", "Pune")).status())
                .as("same (now inactive) plan is not a change").isEqualTo(200);
        Plan other = data.customPlan("Other Retired", Map.of(), Map.of());
        jdbc.update("update plans set active = false where id = ?", other.getId());
        assertThat(admin("PATCH", COMMUNITIES + "/" + community.getId(), Map.of("planId", other.getId().toString())).code()).isEqualTo("PLAN_INACTIVE");
    }

    @Test
    void unknownCommunitiesAre404() {
        UUID missing = UUID.randomUUID();

        assertThat(adminGet(COMMUNITIES + "/" + missing).status()).isEqualTo(404);
        assertThat(adminGet(COMMUNITIES + "/" + missing + "/overview").status()).isEqualTo(404);
        assertThat(adminGet(COMMUNITIES + "/" + missing + "/export").status()).isEqualTo(404);
        assertThat(admin("PATCH", COMMUNITIES + "/" + missing, Map.of("city", "x")).status()).isEqualTo(404);
        assertThat(adminPost(COMMUNITIES + "/" + missing + "/suspend", Map.of("reason", "x")).status()).isEqualTo(404);
    }

    // ---- reset admin password -------------------------------------------------------------------

    @Test
    void resettingAnActiveAdminQueuesAResetEmailAndRevokesEverySession() {
        Community community = data.community();
        var owner = users.communityAdminOf(community);
        jdbc.update("update communities set owner_user_id = ? where id = ?", owner.id(), community.getId());
        Session laptop = loginOk(owner);
        Session phone = loginOk(owner);

        ApiClient.Response response = adminPost(COMMUNITIES + "/" + community.getId() + "/reset-admin-password", Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().get("action").asString()).isEqualTo("RESET_EMAIL_SENT");
        assertThat(emailsTo(owner.email(), "password-reset")).hasSize(1);
        assertThat(refresh(laptop.refreshToken()).status()).isEqualTo(401);
        assertThat(refresh(phone.refreshToken()).status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'COMMUNITY_ADMIN_PASSWORD_RESET' and community_id = ? and actor_user_id = ?", Long.class, community.getId(), superAdmin.id())).isOne();
        String token = tokenFromEmail("password-reset", owner.email());
        assertThat(api.post("/api/v1/auth/password/reset", Map.of("token", token, "newPassword", "Brand-New-Strong-Pass-5")).status()).isEqualTo(204);
    }

    @Test
    void resettingAnAdminWhoNeverAcceptedResendsTheInvitation() {
        String ownerEmail = "owner-" + TestData.unique() + "@example.test";
        UUID id = UUID.fromString(adminPost(COMMUNITIES, newCommunityBody(starter(), "Never Accepted", ownerEmail)).json().get("id").asString());
        String first = tokenFromEmail("invitation", ownerEmail);

        ApiClient.Response response = adminPost(COMMUNITIES + "/" + id + "/reset-admin-password", Map.of());

        assertThat(response.json().get("action").asString()).isEqualTo("INVITATION_RESENT");
        assertThat(emailsTo(ownerEmail, "invitation")).hasSize(2);
        assertThat(api.post("/api/v1/auth/accept-invite", Map.of("token", first, "newPassword", "Correct-Horse-9-Staple")).status()).as("the old link was retired").isEqualTo(400);
        assertThat(api.post("/api/v1/auth/accept-invite", Map.of("token", tokenFromEmail("invitation", ownerEmail), "newPassword", "Correct-Horse-9-Staple")).status()).isEqualTo(200);
    }

    @Test
    void resetRefusesDisabledAccountsAndForeignUsers() {
        Community community = data.community();
        var owner = users.communityAdminOf(community);
        jdbc.update("update communities set owner_user_id = ? where id = ?", owner.id(), community.getId());
        var stranger = users.communityAdmin();

        assertThat(adminPost(COMMUNITIES + "/" + community.getId() + "/reset-admin-password", Map.of("userId", stranger.id().toString())).status())
                .as("a user who does not administer this community").isEqualTo(404);
        jdbc.update("update users set status = 'DISABLED' where id = ?", owner.id());
        assertThat(adminPost(COMMUNITIES + "/" + community.getId() + "/reset-admin-password", Map.of()).code()).isEqualTo("INVALID_STATE_TRANSITION");
    }

    // ---- export ---------------------------------------------------------------------------------

    @Test
    void exportsJsonWithProfileOwnerMemberCountAndSubscriptionHistoryAndAudits() {
        Community community = data.community();
        var owner = users.communityAdminOf(community);
        jdbc.update("update communities set owner_user_id = ? where id = ?", owner.id(), community.getId());
        data.member(community);
        data.member(community);
        LocalDate today = LocalDate.now();
        insertSubscription(community, starter(), today.minusDays(100), today.plusDays(265), "ACTIVE", "REF-" + TestData.unique(), "4990.00");

        ApiClient.Response response = adminGet(COMMUNITIES + "/" + community.getId() + "/export");

        assertThat(response.status()).isEqualTo(200);
        var json = response.json();
        assertThat(json.get("membersCount").asInt()).isEqualTo(2);
        assertThat(json.get("profile").get("owner").get("email").asString()).isEqualToIgnoringCase(owner.email());
        assertThat(json.get("subscriptionHistory").size()).isOne();
        assertThat(json.get("subscriptionHistory").get(0).get("amount").asString()).as("money is a string").isEqualTo("4990.00");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'COMMUNITY_EXPORTED' and community_id = ?", Long.class, community.getId())).isOne();
    }

    @Test
    void exportsCsvInLongFormatThatCannotRunAsASpreadsheetFormula() {
        Community community = data.community();
        jdbc.update("update communities set name = ?, contact_name = ? where id = ?", "=HYPERLINK(\"http://evil\",\"x\")", "+1-evil, \"quoted\"", community.getId());

        ApiClient.Response response = adminGet(COMMUNITIES + "/" + community.getId() + "/export?format=csv");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/csv");
        assertThat(response.header("Content-Disposition")).contains("attachment").contains("community-" + community.getSlug() + ".csv");
        String csv = response.body();
        assertThat(csv).startsWith("section,item,field,value");
        assertThat(csv).contains("profile,,slug," + community.getSlug());
        assertThat(csv).as("a leading = is neutralised").contains("profile,,name,\"'=HYPERLINK(").doesNotContain(",=HYPERLINK");
        assertThat(csv).as("a leading + is neutralised, commas and quotes are escaped").contains("\"'+1-evil, \"\"quoted\"\"\"");
        assertThat(adminGet(COMMUNITIES + "/" + community.getId() + "/export?format=xml").status()).isEqualTo(400);
    }

    // ---- support overview -----------------------------------------------------------------------

    @Test
    void theSupportOverviewShowsCountsFinanceAndActivityAndEveryCallIsAudited() {
        Community community = data.community();
        var active = data.member(community);
        var inactive = data.member(community);
        jdbc.update("update members set status = 'INACTIVE' where id = ?", inactive.getId());
        var invoice = data.issuedInvoice(community, active, "INV-S/1", "1000.00");
        data.issuedInvoice(community, active, "INV-S/2", "250.50");
        jdbc.update("update invoices set amount_paid = 400.00, status = 'PARTIAL' where id = ?", invoice.getId());
        var category = data.category(community, com.amanahconnect.ledger.LedgerType.INCOME, "Donations");
        jdbc.update("insert into ledger_entries (community_id, type, category_id, amount, entry_date, title, created_by) values (?, 'INCOME', ?, 700.00, current_date, 'Gift', ?)", community.getId(), category.getId(), superAdmin.id());
        jdbc.update("insert into audit_logs (actor_user_id, community_id, action, entity_type, entity_id, before, after) values (?, ?, 'MEMBER_CREATED', 'Member', ?, '{\"secret\":\"x\"}'::jsonb, '{\"email\":\"private@example.test\"}'::jsonb)", superAdmin.id(), community.getId(), active.getId());

        ApiClient.Response first = adminGet(COMMUNITIES + "/" + community.getId() + "/overview");
        adminGet(COMMUNITIES + "/" + community.getId() + "/overview");

        assertThat(first.status()).as(first.body()).isEqualTo(200);
        var json = first.json();
        assertThat(json.get("counts").get("membersActive").asInt()).isOne();
        assertThat(json.get("counts").get("membersInactive").asInt()).isOne();
        assertThat(json.get("counts").get("openInvoices").asInt()).isEqualTo(2);
        assertThat(json.get("finance").get("invoicedOpen").asString()).isEqualTo("1250.50");
        assertThat(json.get("finance").get("collectedOnOpenAndPaid").asString()).isEqualTo("400.00");
        assertThat(json.get("finance").get("outstanding").asString()).isEqualTo("850.50");
        assertThat(json.get("finance").get("incomeThisFinancialYear").asString()).isEqualTo("700.00");
        assertThat(json.get("finance").get("netThisFinancialYear").asString()).isEqualTo("700.00");
        assertThat(json.get("recentActivity").toString()).contains("MEMBER_CREATED");
        assertThat(first.body()).as("activity lines never carry before/after payloads").doesNotContain("private@example.test").doesNotContain("secret");

        List<Map<String, Object>> views = jdbc.queryForList("select * from audit_logs where action = 'SUPPORT_VIEW' and community_id = ?", community.getId());
        assertThat(views).as("one audit row per call").hasSize(2);
        assertThat(views).allSatisfy(v -> assertThat(v.get("actor_user_id")).isEqualTo(superAdmin.id()));
    }

    @Test
    void subscriptionHistoryListsEveryPaymentWithItsDisplayedStatus() {
        Community community = data.community();
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        insertSubscription(community, starter(), today.minusDays(400), today.minusDays(35), "EXPIRED", "old", "100.00");
        insertSubscription(community, starter(), today.minusDays(30), today.plusDays(335), "ACTIVE", "current", "200.00");

        var history = adminGet(COMMUNITIES + "/" + community.getId() + "/subscriptions").json();

        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(history.get(1).get("status").asString()).isEqualTo("EXPIRED");
    }
}

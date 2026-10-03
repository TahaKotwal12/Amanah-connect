package com.amanahconnect.announcement;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.Community;
import com.amanahconnect.support.AbstractDeskIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.AuthTestUsers;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class PlatformAnnouncementIT extends AbstractDeskIT {

    private static final String P = ADMIN + "/announcements";
    private static final String NOTIFY = "/api/v1/community/notifications";

    @Autowired AnnouncementDispatchService dispatch;

    private ApiClient.Response create(Map<String, Object> extra) {
        Map<String, Object> body = mapOf("title", "Platform news " + UUID.randomUUID().toString().substring(0, 6), "body", "<p>New <strong>feature</strong> released.</p>");
        body.putAll(extra);
        return asSuper("POST", P, body);
    }

    private UUID draft(Map<String, Object> extra) {
        ApiClient.Response r = create(extra);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return id(r.json());
    }

    private ApiClient.Response send(UUID id) {
        return asSuper("POST", P + "/" + id + "/send", null);
    }

    private long mailsTo(String address, UUID announcement) {
        return count("select count(*) from email_outbox where to_email = ? and template = 'platform-announcement' and payload ->> 'announcementId' = ?", address, announcement.toString());
    }

    private List<String> notificationIds(Session s) {
        List<String> out = new ArrayList<>();
        call(s, "GET", NOTIFY + "?size=100", null).json().get("items").forEach(i -> out.add(i.get("id").asString()));
        return out;
    }

    private List<String> targeting(Community... communities) {
        List<String> ids = new ArrayList<>();
        for (Community c : communities) ids.add(c.getId().toString());
        return ids;
    }

    // ---- creating ----------------------------------------------------------------------------------------------------------

    @Test
    void createsADraftThatTargetsEveryCommunityByDefault() {
        ApiClient.Response r = create(Map.of());

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        JsonNode a = r.json();
        assertThat(a.get("status").asString()).isEqualTo("DRAFT");
        assertThat(a.get("kind").asString()).isEqualTo("ANNOUNCEMENT");
        assertThat(a.get("communityIds").isNull()).as("null means everyone").isTrue();
        assertThat(a.get("sendEmail").asBoolean()).isFalse();
        assertThat(a.get("banner").asBoolean()).isFalse();
        assertThat(count("select count(*) from audit_logs where action = 'PLATFORM_ANNOUNCEMENT_CREATED' and entity_id = ?", id(a))).isEqualTo(1);
    }

    @Test
    void hostileHtmlIsCleaned() {
        String evil = "<p>Offer</p><script>alert(1)</script><img src=x onerror=alert(1)><a href=\"javascript:alert(1)\">go</a>";
        JsonNode a = create(mapOf("body", evil, "kind", "OFFER")).json();

        assertThat(a.get("bodyHtml").asString().toLowerCase()).doesNotContain("<script", "onerror", "javascript:", "<img");
        assertThat(a.get("bodyText").asString()).isEqualTo("Offer\ngo");
        assertThat(create(mapOf("body", "<script>x()</script>")).status()).isEqualTo(400);
    }

    @Test
    void validatesTheRequest() {
        assertThat(create(mapOf("title", "")).status()).isEqualTo(400);
        assertThat(create(mapOf("kind", "SPAM")).status()).isEqualTo(400);
        assertThat(create(mapOf("communityIds", List.of(UUID.randomUUID().toString()))).status()).as("unknown community").isEqualTo(400);
        assertThat(create(mapOf("expiresAt", Instant.now().minusSeconds(5).toString())).status()).isEqualTo(400);
        assertThat(create(mapOf("scheduledAt", Instant.now().minusSeconds(5).toString())).status()).isEqualTo(400);
    }

    @Test
    void editsADraftAndRetargets() {
        UUID id = draft(Map.of());

        JsonNode targeted = asSuper("PATCH", P + "/" + id, mapOf("communityIds", targeting(communityA), "banner", true, "kind", "MAINTENANCE", "title", "Maintenance window")).json();
        assertThat(targeted.get("communityIds")).hasSize(1);
        assertThat(targeted.get("banner").asBoolean()).isTrue();
        assertThat(targeted.get("kind").asString()).isEqualTo("MAINTENANCE");

        JsonNode everyone = asSuper("PATCH", P + "/" + id, mapOf("allCommunities", true)).json();
        assertThat(everyone.get("communityIds").isNull()).isTrue();
        assertThat(asSuper("PATCH", P + "/" + id, mapOf("allCommunities", true, "communityIds", targeting(communityA))).status()).isEqualTo(400);

        Instant expiry = Instant.now().plus(2, ChronoUnit.DAYS);
        assertThat(asSuper("PATCH", P + "/" + id, mapOf("expiresAt", expiry.toString())).json().get("expiresAt").isNull()).isFalse();
        assertThat(asSuper("PATCH", P + "/" + id, mapOf("clearExpiry", true)).json().get("expiresAt").isNull()).isTrue();
        assertThat(asSuper("PATCH", P + "/" + UUID.randomUUID(), mapOf("title", "x")).status()).isEqualTo(404);
    }

    @Test
    void scheduleAndUnschedule() {
        UUID id = draft(Map.of());
        Instant at = Instant.now().plus(3, ChronoUnit.HOURS);

        assertThat(asSuper("POST", P + "/" + id + "/schedule", mapOf("scheduledAt", at.toString())).json().get("status").asString()).isEqualTo("SCHEDULED");
        assertThat(asSuper("POST", P + "/" + id + "/schedule", mapOf("scheduledAt", Instant.now().minusSeconds(1).toString())).status()).isEqualTo(400);
        assertThat(asSuper("PATCH", P + "/" + id, mapOf("unschedule", true)).json().get("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    void listAndDetail() {
        UUID d = draft(Map.of());
        UUID s = draft(Map.of());
        send(s);

        List<String> all = new ArrayList<>();
        asSuper("GET", P + "?size=100", null).json().get("items").forEach(i -> all.add(i.get("id").asString()));
        List<String> sent = new ArrayList<>();
        asSuper("GET", P + "?status=SENT&size=100", null).json().get("items").forEach(i -> sent.add(i.get("id").asString()));

        assertThat(all).contains(d.toString(), s.toString());
        assertThat(sent).contains(s.toString()).doesNotContain(d.toString());
        assertThat(asSuper("GET", P + "/" + s, null).json().get("status").asString()).isEqualTo("SENT");
        assertThat(asSuper("GET", P + "/" + UUID.randomUUID(), null).status()).isEqualTo(404);
    }

    // ---- sending -----------------------------------------------------------------------------------------------------------

    @Test
    void emailsTheAdminsOfTheTargetedCommunitiesOnly() {
        AuthTestUsers.TestUser colleague = users.extraAdminOf(communityA);
        UUID id = draft(Map.of("communityIds", targeting(communityA), "sendEmail", true));

        JsonNode sent = send(id).json();

        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        assertThat(sent.get("delivery").get("recipientsTotal").asInt()).isEqualTo(2);
        assertThat(sent.get("delivery").get("emailsQueued").asInt()).isEqualTo(2);
        assertThat(mailsTo(adminA.email(), id)).isEqualTo(1);
        assertThat(mailsTo(colleague.email(), id)).isEqualTo(1);
        assertThat(mailsTo(adminB.email(), id)).as("B was not targeted").isZero();
        Map<String, Object> row = jdbc.queryForMap("select * from email_outbox where to_email = ? and payload ->> 'announcementId' = ?", adminA.email(), id.toString());
        assertThat(row.get("community_id")).as("platform mail never uses a community's quota").isNull();
        assertThat(row.get("payload").toString()).contains("New <strong>feature</strong> released.");
        assertThat(count("select count(*) from audit_logs where action = 'PLATFORM_ANNOUNCEMENT_SENT' and entity_id = ?", id)).isEqualTo(1);
    }

    @Test
    void anAdminOfTwoTargetedCommunitiesGetsOneEmail() {
        AuthTestUsers.TestUser both = users.extraAdminOf(communityA);
        jdbc.update("insert into community_users (id, community_id, user_id, role) values (gen_random_uuid(), ?, ?, 'ADMIN')", communityB.getId(), both.id());
        UUID id = draft(Map.of("communityIds", targeting(communityA, communityB), "sendEmail", true));

        send(id);

        assertThat(mailsTo(both.email(), id)).isEqualTo(1);
    }

    @Test
    void doesNotEmailSuspendedCommunitiesOrDisabledAdmins() {
        AuthTestUsers.TestUser disabled = users.extraAdminOf(communityA);
        jdbc.update("update users set status = 'DISABLED' where id = ?", disabled.id());
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityB.getId());
        UUID id = draft(Map.of("communityIds", targeting(communityA, communityB), "sendEmail", true));
        try {
            send(id);
        } finally {
            jdbc.update("update communities set status = 'ACTIVE' where id = ?", communityB.getId());
        }

        assertThat(mailsTo(adminA.email(), id)).isEqualTo(1);
        assertThat(mailsTo(disabled.email(), id)).isZero();
        assertThat(mailsTo(adminB.email(), id)).as("suspended community").isZero();
    }

    @Test
    void everyActiveCommunityIsReachedWhenNoneAreChosen() {
        UUID id = draft(Map.of("sendEmail", true));

        JsonNode sent = send(id).json();

        assertThat(mailsTo(adminA.email(), id)).isEqualTo(1);
        assertThat(mailsTo(adminB.email(), id)).isEqualTo(1);
        assertThat(sent.get("delivery").get("recipientsTotal").asInt()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void noEmailFlagMeansBannerOnly() {
        UUID id = draft(Map.of("communityIds", targeting(communityA), "banner", true));

        JsonNode sent = send(id).json();

        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        assertThat(sent.get("delivery").get("emailsQueued").asInt()).isZero();
        assertThat(mailsTo(adminA.email(), id)).isZero();
        assertThat(call(sessionA, "GET", NOTIFY + "/summary", null).json().get("banners")).hasSize(1);
    }

    @Test
    void sendingTwiceOrAtOnceEmailsOnce() throws Exception {
        UUID id = draft(Map.of("communityIds", targeting(communityA), "sendEmail", true));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int i = 0; i < 4; i++) jobs.add(() -> send(id).status());
            for (Future<Integer> f : pool.invokeAll(jobs)) statuses.add(f.get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409, 409, 409);
        assertThat(mailsTo(adminA.email(), id)).isEqualTo(1);
        ApiClient.Response again = send(id);
        assertThat(again.code()).isEqualTo("ANNOUNCEMENT_STATE");
        ApiClient.Response edit = asSuper("PATCH", P + "/" + id, mapOf("title", "late"));
        assertThat(edit.status()).isEqualTo(409);
        assertThat(edit.code()).isEqualTo("ANNOUNCEMENT_NOT_EDITABLE");
    }

    @Test
    void previewAndTestSendQueueNothingToCommunities() {
        UUID id = draft(Map.of("communityIds", targeting(communityA), "sendEmail", true, "banner", true));

        JsonNode p = asSuper("GET", P + "/" + id + "/preview", null).json();
        ApiClient.Response test = asSuper("POST", P + "/" + id + "/send-test", null);

        assertThat(p.get("communities").asInt()).isEqualTo(1);
        assertThat(p.get("adminRecipients").asInt()).isEqualTo(1);
        assertThat(p.get("banner").asBoolean()).isTrue();
        assertThat(test.status()).as(test.body()).isEqualTo(200);
        assertThat(mailsTo(superAdmin.email(), id)).isEqualTo(1);
        assertThat(mailsTo(adminA.email(), id)).isZero();
        assertThat(jdbc.queryForObject("select status from announcements where id = ?", String.class, id)).isEqualTo("DRAFT");
    }

    // ---- scheduled dispatch -------------------------------------------------------------------------------------------------

    @Test
    void aScheduledPlatformAnnouncementGoesOutWhenDue() {
        UUID id = draft(Map.of("communityIds", targeting(communityA), "sendEmail", true, "scheduledAt", Instant.now().plus(1, ChronoUnit.HOURS).toString()));
        dispatch.dispatchDue(Instant.now());
        assertThat(jdbc.queryForObject("select status from announcements where id = ?", String.class, id)).as("not due yet").isEqualTo("SCHEDULED");

        jdbc.update("update announcements set scheduled_at = now() - interval '1 minute' where id = ?", id);
        dispatch.dispatchDue(Instant.now());
        dispatch.dispatchDue(Instant.now());

        assertThat(jdbc.queryForObject("select status from announcements where id = ?", String.class, id)).isEqualTo("SENT");
        assertThat(mailsTo(adminA.email(), id)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'PLATFORM_ANNOUNCEMENT_SENT' and entity_id = ?", id)).isEqualTo(1);
    }

    // ---- the community admin's notification area ----------------------------------------------------------------------------

    @Test
    void onlyTargetedCommunitiesSeeIt() {
        UUID id = draft(Map.of("communityIds", targeting(communityA), "banner", true));
        assertThat(notificationIds(sessionA)).as("a draft is invisible").doesNotContain(id.toString());
        send(id);

        assertThat(notificationIds(sessionA)).contains(id.toString());
        assertThat(notificationIds(sessionB)).doesNotContain(id.toString());
        assertThat(call(sessionB, "POST", NOTIFY + "/" + id + "/read", null).status()).as("not addressed to B").isEqualTo(404);
        assertThat(call(sessionA, "POST", NOTIFY + "/" + UUID.randomUUID() + "/read", null).status()).isEqualTo(404);
        assertThat(call(sessionB, "GET", NOTIFY + "/summary", null).json().get("banners")).isEmpty();
        assertThat(count("select count(*) from announcement_reads where announcement_id = ?", id)).isZero();
    }

    @Test
    void acommunityCreatedAfterAnAnnouncementDoesNotInheritIt() {
        UUID id = draft(Map.of("banner", true));
        send(id);
        Community late = data.community();
        AuthTestUsers.TestUser lateAdmin = users.communityAdminOf(late);
        Session lateSession = loginOk(lateAdmin);

        assertThat(notificationIds(lateSession)).doesNotContain(id.toString());
        assertThat(notificationIds(sessionA)).contains(id.toString());
    }

    @Test
    void readStateIsPerAdmin() {
        AuthTestUsers.TestUser colleague = users.extraAdminOf(communityA);
        Session colleagueSession = loginOk(colleague);
        UUID id = draft(Map.of("communityIds", targeting(communityA), "banner", true));
        send(id);
        assertThat(call(sessionA, "GET", NOTIFY + "/summary", null).json().get("unread").asLong()).isEqualTo(1);

        JsonNode after = call(sessionA, "POST", NOTIFY + "/" + id + "/read", null).json();

        assertThat(after.get("unread").asLong()).isZero();
        assertThat(after.get("banners")).as("reading dismisses the banner").isEmpty();
        assertThat(call(colleagueSession, "GET", NOTIFY + "/summary", null).json().get("unread").asLong()).as("the colleague has not read it").isEqualTo(1);
        assertThat(call(colleagueSession, "GET", NOTIFY + "/summary", null).json().get("banners")).hasSize(1);
        JsonNode mine = call(sessionA, "GET", NOTIFY, null).json().get("items");
        assertThat(mine.get(0).get("read").asBoolean()).isTrue();
        assertThat(call(sessionA, "GET", NOTIFY + "?unread=true", null).json().get("items")).isEmpty();
        assertThat(call(colleagueSession, "GET", NOTIFY + "?unread=true", null).json().get("items")).hasSize(1);
        assertThat(asSuper("GET", P + "/" + id, null).json().get("readCount").asLong()).isEqualTo(1);
    }

    @Test
    void markingReadTwiceIsHarmlessAndAuditedOnce() {
        UUID id = draft(Map.of("communityIds", targeting(communityA)));
        send(id);

        call(sessionA, "POST", NOTIFY + "/" + id + "/read", null);
        assertThat(call(sessionA, "POST", NOTIFY + "/" + id + "/read", null).status()).isEqualTo(200);

        assertThat(count("select count(*) from announcement_reads where announcement_id = ?", id)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'NOTIFICATION_READ'", communityA.getId())).isEqualTo(1);
    }

    @Test
    void readAll() {
        UUID one = draft(Map.of("communityIds", targeting(communityA)));
        UUID two = draft(Map.of("communityIds", targeting(communityA)));
        send(one);
        send(two);
        long before = call(sessionA, "GET", NOTIFY + "/summary", null).json().get("unread").asLong();
        assertThat(before).isGreaterThanOrEqualTo(2);

        JsonNode after = call(sessionA, "POST", NOTIFY + "/read-all", null).json();

        assertThat(after.get("unread").asLong()).isZero();
        assertThat(call(sessionA, "POST", NOTIFY + "/read-all", null).status()).isEqualTo(200);
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'NOTIFICATIONS_READ_ALL'", communityA.getId())).isEqualTo(1);
    }

    @Test
    void anExpiredAnnouncementDisappears() {
        UUID id = draft(Map.of("communityIds", targeting(communityA), "banner", true, "expiresAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()));
        send(id);
        assertThat(notificationIds(sessionA)).contains(id.toString());

        jdbc.update("update announcements set expires_at = now() - interval '1 minute' where id = ?", id);

        assertThat(notificationIds(sessionA)).doesNotContain(id.toString());
        assertThat(call(sessionA, "GET", NOTIFY + "/summary", null).json().get("banners")).isEmpty();
        assertThat(call(sessionA, "POST", NOTIFY + "/" + id + "/read", null).status()).isEqualTo(404);
    }

    @Test
    void offersCarryTheirKind() {
        UUID id = draft(Map.of("communityIds", targeting(communityA), "kind", "OFFER", "banner", true, "title", "20% off yearly plans"));
        send(id);

        JsonNode banner = call(sessionA, "GET", NOTIFY + "/summary", null).json().get("banners").get(0);

        assertThat(banner.get("kind").asString()).isEqualTo("OFFER");
        assertThat(banner.get("title").asString()).isEqualTo("20% off yearly plans");
        assertThat(banner.get("bodyHtml").asString()).contains("<strong>feature</strong>");
    }

    @Test
    void notificationsAreIsolatedPerCommunity() {
        UUID forA = draft(Map.of("communityIds", targeting(communityA)));
        UUID forB = draft(Map.of("communityIds", targeting(communityB)));
        send(forA);
        send(forB);

        assertListHidesNotification(forB);
        assertTenantSingleton("GET", NOTIFY + "/summary", null, () -> call(sessionB, "GET", NOTIFY + "/summary", null).body());
        assertTenantSingleton("POST", NOTIFY + "/read-all", null, () -> call(sessionB, "GET", NOTIFY + "/summary", null).body());
        assertThat(call(sessionA, "POST", NOTIFY + "/" + forB + "/read", null).status()).isEqualTo(404);
        assertThat(count("select count(*) from announcement_reads where announcement_id = ? and user_id = ?", forB, adminA.id())).isZero();
        markCovered("POST", NOTIFY + "/" + forB + "/read");
        markCovered("GET", NOTIFY);
    }

    private void assertListHidesNotification(UUID idOfB) {
        assertThat(call(sessionA, "GET", NOTIFY + "?size=100", null).body()).doesNotContain(idOfB.toString());
        assertThat(call(sessionB, "GET", NOTIFY + "?size=100", null).body()).contains(idOfB.toString());
    }

    // ---- who may call what -------------------------------------------------------------------------------------------------

    @Test
    void onlySuperAdminsManagePlatformAnnouncementsAndOnlyCommunityAdminsReadNotifications() {
        UUID id = draft(Map.of());

        assertThat(call(sessionA, "GET", P, null).status()).isEqualTo(403);
        assertThat(call(sessionA, "POST", P, mapOf("title", "x", "body", "<p>y</p>")).status()).isEqualTo(403);
        assertThat(call(sessionA, "PATCH", P + "/" + id, mapOf("title", "x")).status()).isEqualTo(403);
        assertThat(call(sessionA, "POST", P + "/" + id + "/send", null).status()).isEqualTo(403);
        assertThat(api.call("GET", P, null).status()).isEqualTo(401);
        assertThat(asSuper("GET", NOTIFY, null).status()).isIn(401, 403);
        assertThat(jdbc.queryForObject("select status from announcements where id = ?", String.class, id)).isEqualTo("DRAFT");
    }
}

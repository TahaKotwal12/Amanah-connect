package com.amanahconnect.announcement;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.AbstractDeskIT;
import com.amanahconnect.support.ApiClient;
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

class AnnouncementIT extends AbstractDeskIT {

    private static final String N = "/api/v1/community/announcements";

    @Autowired AnnouncementDispatchService dispatch;
    @Autowired AnnouncementJobs jobs;

    private ApiClient.Response create(Session s, Map<String, Object> extra) {
        Map<String, Object> body = mapOf("title", "Water supply " + UUID.randomUUID().toString().substring(0, 6), "body", "<p>No water on <strong>Monday</strong>.</p>");
        body.putAll(extra);
        return call(s, "POST", N, body);
    }

    private UUID draft(Map<String, Object> extra) {
        ApiClient.Response r = create(sessionA, extra);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return id(r.json());
    }

    private ApiClient.Response send(UUID id) {
        return call(sessionA, "POST", N + "/" + id + "/send", null);
    }

    private long mailsFor(UUID announcement) {
        return count("select count(*) from email_outbox where template = 'member-announcement' and payload ->> 'announcementId' = ?", announcement.toString());
    }

    private UUID memberWithMail(String name, boolean consent) {
        return member(sessionA, name, email(), consent);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from announcements where id = ?", String.class, id);
    }

    // ---- content and the sanitiser end to end ------------------------------------------------------------------------------

    @Test
    void createsADraftWithDefaults() {
        ApiClient.Response r = create(sessionA, Map.of());

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        JsonNode a = r.json();
        assertThat(a.get("status").asString()).isEqualTo("DRAFT");
        assertThat(a.get("audience").asString()).isEqualTo("ALL_ACTIVE");
        assertThat(a.get("sendEmail").asBoolean()).isFalse();
        assertThat(a.get("bodyHtml").asString()).isEqualTo("<p>No water on <strong>Monday</strong>.</p>");
        assertThat(a.get("bodyText").asString()).isEqualTo("No water on Monday.");
        assertThat(a.get("delivery").get("recipientsTotal").asInt()).isZero();
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'ANNOUNCEMENT_CREATED'", communityA.getId())).isEqualTo(1);
    }

    @Test
    void hostileHtmlIsCleanedBeforeItIsStoredOrSent() {
        String address = email();
        member(sessionA, "Reader", address, true);
        String evil = "<p onclick=\"steal()\">Hello</p><script>alert(document.cookie)</script><img src=x onerror=alert(1)>"
                + "<a href=\"javascript:alert(1)\">click</a><a href=\"https://ok.test\" onmouseover=\"x()\">fine</a><iframe src=\"https://evil.test\"></iframe>";
        ApiClient.Response r = create(sessionA, Map.of("body", evil, "sendEmail", true));
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        UUID id = id(r.json());

        String shown = r.json().get("bodyHtml").asString();
        String stored = jdbc.queryForObject("select body from announcements where id = ?", String.class, id);
        send(id);
        String mailed = outbox(address, "member-announcement").get(0).get("payload").toString();

        for (String where : List.of(shown, stored, mailed)) {
            assertThat(where.toLowerCase()).doesNotContain("<script", "onerror", "onclick", "onmouseover", "javascript:", "<iframe", "<img", "document.cookie");
            assertThat(where).contains("Hello", "fine");
        }
        assertThat(shown).contains("href=\"https://ok.test\"").contains("rel=\"nofollow noopener noreferrer\"");
    }

    @Test
    void editingAlsoSanitises() {
        UUID id = draft(Map.of());

        ApiClient.Response r = call(sessionA, "PATCH", N + "/" + id, mapOf("body", "<p>ok</p><script>x()</script>"));

        assertThat(r.json().get("bodyHtml").asString()).isEqualTo("<p>ok</p>");
    }

    @Test
    void aBodyWithNothingSafeInItIsRejected() {
        assertThat(create(sessionA, Map.of("body", "<script>alert(1)</script>")).status()).isEqualTo(400);
        assertThat(create(sessionA, Map.of("body", "<img src=x onerror=alert(1)>")).status()).isEqualTo(400);
        assertThat(create(sessionA, Map.of("body", "<p>&nbsp;</p>")).status()).isEqualTo(400);
        UUID id = draft(Map.of());
        assertThat(call(sessionA, "PATCH", N + "/" + id, mapOf("body", "<style>x{}</style>")).status()).isEqualTo(400);
        assertThat(create(sessionA, Map.of("title", "")).status()).isEqualTo(400);
        assertThat(create(sessionA, Map.of("title", "x".repeat(201))).status()).isEqualTo(400);
        assertThat(create(sessionA, Map.of("body", "<p>" + "x".repeat(20001) + "</p>")).status()).isEqualTo(400);
    }

    @Test
    void validatesTheAudience() {
        UUID mine = memberA("Mine");
        UUID theirs = member(sessionB, "Theirs", email(), true);

        assertThat(create(sessionA, Map.of("audience", "GROUP")).status()).as("group required").isEqualTo(400);
        assertThat(create(sessionA, Map.of("audience", "SELECTED")).status()).as("members required").isEqualTo(400);
        assertThat(create(sessionA, Map.of("audience", "ALL_ACTIVE", "group", "A")).status()).isEqualTo(400);
        assertThat(create(sessionA, Map.of("audience", "COMMUNITY_ADMINS")).status()).as("that is for the platform").isEqualTo(400);
        assertThat(create(sessionA, Map.of("audience", "SELECTED", "memberIds", List.of(theirs.toString()))).status()).as("another community's member").isEqualTo(400);
        assertThat(create(sessionA, Map.of("audience", "SELECTED", "memberIds", List.of(UUID.randomUUID().toString()))).status()).isEqualTo(400);
        assertThat(create(sessionA, Map.of("audience", "SELECTED", "memberIds", List.of(mine.toString(), mine.toString()))).status()).as("duplicates collapse").isEqualTo(201);
    }

    // ---- life cycle --------------------------------------------------------------------------------------------------------

    @Test
    void draftScheduledAndBack() {
        UUID id = draft(Map.of());
        Instant at = Instant.now().plus(2, ChronoUnit.HOURS);

        JsonNode scheduled = call(sessionA, "POST", N + "/" + id + "/schedule", mapOf("scheduledAt", at.toString())).json();
        assertThat(scheduled.get("status").asString()).isEqualTo("SCHEDULED");
        assertThat(Instant.parse(scheduled.get("scheduledAt").asString())).isEqualTo(at);

        JsonNode back = call(sessionA, "POST", N + "/" + id + "/unschedule", null).json();
        assertThat(back.get("status").asString()).isEqualTo("DRAFT");
        assertThat(back.get("scheduledAt").isNull()).isTrue();
        assertThat(call(sessionA, "POST", N + "/" + id + "/unschedule", null).status()).as("not scheduled any more").isEqualTo(409);
        assertThat(call(sessionA, "PATCH", N + "/" + id, mapOf("scheduledAt", at.toString())).json().get("status").asString()).isEqualTo("SCHEDULED");
        assertThat(call(sessionA, "PATCH", N + "/" + id, mapOf("unschedule", true)).json().get("status").asString()).isEqualTo("DRAFT");
        assertThat(call(sessionA, "PATCH", N + "/" + id, mapOf("unschedule", true, "scheduledAt", at.toString())).status()).isEqualTo(400);
    }

    @Test
    void aScheduleMustBeInTheFuture() {
        UUID id = draft(Map.of());
        assertThat(call(sessionA, "POST", N + "/" + id + "/schedule", mapOf("scheduledAt", Instant.now().minusSeconds(5).toString())).status()).isEqualTo(400);
        assertThat(create(sessionA, Map.of("scheduledAt", Instant.now().minusSeconds(5).toString())).status()).isEqualTo(400);
        assertThat(call(sessionA, "POST", N + "/" + id + "/schedule", mapOf()).status()).isEqualTo(400);
        ApiClient.Response created = create(sessionA, Map.of("scheduledAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()));
        assertThat(created.json().get("status").asString()).isEqualTo("SCHEDULED");
    }

    @Test
    void aSentAnnouncementIsFinal() {
        UUID id = draft(Map.of());
        assertThat(send(id).status()).isEqualTo(200);

        ApiClient.Response edit = call(sessionA, "PATCH", N + "/" + id, mapOf("title", "Changed afterwards"));
        assertThat(edit.status()).isEqualTo(409);
        assertThat(edit.code()).isEqualTo("ANNOUNCEMENT_NOT_EDITABLE");
        assertThat(call(sessionA, "POST", N + "/" + id + "/schedule", mapOf("scheduledAt", Instant.now().plus(1, ChronoUnit.DAYS).toString())).status()).isEqualTo(409);
        assertThat(call(sessionA, "POST", N + "/" + id + "/unschedule", null).status()).isEqualTo(409);
        ApiClient.Response again = send(id);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ANNOUNCEMENT_STATE");
    }

    @Test
    void listsAndFiltersByStatus() {
        UUID d = draft(Map.of());
        UUID s = draft(Map.of());
        send(s);
        create(sessionB, Map.of());

        List<String> all = new ArrayList<>();
        call(sessionA, "GET", N, null).json().get("items").forEach(i -> all.add(i.get("id").asString()));
        List<String> sent = new ArrayList<>();
        call(sessionA, "GET", N + "?status=SENT", null).json().get("items").forEach(i -> sent.add(i.get("id").asString()));

        assertThat(all).containsExactlyInAnyOrder(d.toString(), s.toString());
        assertThat(sent).containsExactly(s.toString());
        assertThat(call(sessionA, "GET", N + "?status=NOPE", null).status()).isEqualTo(400);
        assertThat(call(sessionA, "GET", N + "?sort=body", null).status()).isEqualTo(400);
    }

    // ---- sending, consent and delivery counts --------------------------------------------------------------------------------

    @Test
    void emailsOnlyMembersWithAnAddressWhoAgreed() {
        UUID ok1 = memberWithMail("A Consenting", true);
        UUID ok2 = memberWithMail("B Consenting", true);
        memberWithMail("C Declined", false);
        member(sessionA, "D No Address", null, true);
        UUID inactive = memberWithMail("E Inactive", true);
        call(sessionA, "POST", "/api/v1/community/members/" + inactive + "/deactivate", mapOf("reason", "moved"));
        UUID id = draft(Map.of("sendEmail", true));

        JsonNode sent = send(id).json();

        JsonNode delivery = sent.get("delivery");
        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        assertThat(sent.get("sentAt").isNull()).isFalse();
        assertThat(delivery.get("recipientsTotal").asInt()).as("active members only").isEqualTo(4);
        assertThat(delivery.get("emailsQueued").asInt()).isEqualTo(2);
        assertThat(delivery.get("skippedNoConsent").asInt()).isEqualTo(1);
        assertThat(delivery.get("skippedNoEmail").asInt()).isEqualTo(1);
        assertThat(delivery.get("skippedQuota").asInt()).isZero();
        assertThat(mailsFor(id)).isEqualTo(2);
        assertThat(jdbc.queryForList("select to_email from email_outbox where template = 'member-announcement' and payload ->> 'announcementId' = ?", String.class, id.toString())).hasSize(2);
        assertThat(ok1).isNotEqualTo(ok2);
        Map<String, Object> row = jdbc.queryForMap("select * from email_outbox where template = 'member-announcement' and payload ->> 'announcementId' = ? limit 1", id.toString());
        assertThat(row.get("community_id").toString()).isEqualTo(communityA.getId().toString());
        assertThat(row.get("payload").toString()).contains(communityA.getName(), "No water on Monday.");
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'ANNOUNCEMENT_SENT'", communityA.getId())).isEqualTo(1);
    }

    @Test
    void withoutTheEmailFlagNobodyIsEmailed() {
        memberWithMail("Reader", true);
        UUID id = draft(Map.of("sendEmail", false));

        JsonNode sent = send(id).json();

        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        assertThat(sent.get("delivery").get("recipientsTotal").asInt()).isEqualTo(1);
        assertThat(sent.get("delivery").get("emailsQueued").asInt()).isZero();
        assertThat(mailsFor(id)).isZero();
    }

    @Test
    void reachesAGroupOrSelectedMembersOnly() {
        UUID a1 = member(sessionA, "Alice", email(), true);
        UUID a2 = member(sessionA, "Bob", email(), true);
        UUID outsider = member(sessionA, "Carol", email(), true);
        call(sessionA, "PATCH", "/api/v1/community/members/" + a1, mapOf("group", "Block A"));
        call(sessionA, "PATCH", "/api/v1/community/members/" + a2, mapOf("group", "block a"));
        call(sessionA, "PATCH", "/api/v1/community/members/" + outsider, mapOf("group", "Block B"));

        UUID byGroup = draft(Map.of("audience", "GROUP", "group", "BLOCK A", "sendEmail", true));
        UUID selected = draft(Map.of("audience", "SELECTED", "memberIds", List.of(outsider.toString()), "sendEmail", true));
        send(byGroup);
        send(selected);

        assertThat(mailsFor(byGroup)).as("both Block A members, case-insensitively").isEqualTo(2);
        assertThat(mailsFor(selected)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select to_email from email_outbox where payload ->> 'announcementId' = ?", String.class, selected.toString()))
                .isEqualTo(jdbc.queryForObject("select email from members where id = ?", String.class, outsider));
    }

    @Test
    void sendingTwiceNeverEmailsTwice() {
        memberWithMail("Once", true);
        UUID id = draft(Map.of("sendEmail", true));

        assertThat(send(id).status()).isEqualTo(200);
        assertThat(send(id).status()).isEqualTo(409);
        assertThat(mailsFor(id)).isEqualTo(1);
    }

    @Test
    void twoSimultaneousSendsEmailEachMemberOnce() throws Exception {
        for (int i = 0; i < 5; i++) memberWithMail("Racer " + i, true);
        UUID id = draft(Map.of("sendEmail", true));
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
        assertThat(mailsFor(id)).isEqualTo(5);
    }

    // ---- quota -------------------------------------------------------------------------------------------------------------

    @Test
    void theEmailQuotaCapsTheEmailsAndTheRestAreRecordedAsSkipped() {
        List<String> addresses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String address = email();
            addresses.add(address);
            member(sessionA, "Member " + (char) ('A' + i), address, true);
        }
        long queued = count("select count(*) from email_outbox where community_id = ?", communityA.getId());
        Plan limited = data.customPlan("Two emails", Map.of("emails_per_month", queued + 2), Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", limited.getId(), communityA.getId());
        UUID id = draft(Map.of("sendEmail", true));

        JsonNode preview = call(sessionA, "GET", N + "/" + id + "/preview", null).json();
        assertThat(preview.get("quotaRemaining").asLong()).isEqualTo(2);
        assertThat(preview.get("wouldSkipForQuota").asInt()).isEqualTo(3);

        JsonNode sent = send(id).json();

        assertThat(sent.get("status").asString()).as("the announcement still goes out").isEqualTo("SENT");
        assertThat(sent.get("delivery").get("emailsQueued").asInt()).isEqualTo(2);
        assertThat(sent.get("delivery").get("skippedQuota").asInt()).isEqualTo(3);
        assertThat(mailsFor(id)).isEqualTo(2);
        assertThat(count("select count(*) from email_outbox where community_id = ?", communityA.getId())).as("never past the limit").isEqualTo(queued + 2);
        List<String> mailed = jdbc.queryForList("select to_email from email_outbox where payload ->> 'announcementId' = ? order by to_email", String.class, id.toString());
        assertThat(mailed).as("the first members by member number get it").containsExactlyInAnyOrder(addresses.get(0), addresses.get(1));
    }

    @Test
    void anExhaustedQuotaSendsNothingButStillPublishes() {
        memberWithMail("Quiet", true);
        long queued = count("select count(*) from email_outbox where community_id = ?", communityA.getId());
        Plan none = data.customPlan("No emails", Map.of("emails_per_month", queued), Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", none.getId(), communityA.getId());
        UUID id = draft(Map.of("sendEmail", true));

        JsonNode sent = send(id).json();

        assertThat(sent.get("delivery").get("emailsQueued").asInt()).isZero();
        assertThat(sent.get("delivery").get("skippedQuota").asInt()).isEqualTo(1);
        assertThat(sent.get("status").asString()).isEqualTo("SENT");
    }

    @Test
    void anUnlimitedPlanHasNoQuotaNumber() {
        UUID id = draft(Map.of("sendEmail", true));
        Plan unlimited = data.customPlan("Unlimited", Map.of(), Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", unlimited.getId(), communityA.getId());

        JsonNode preview = call(sessionA, "GET", N + "/" + id + "/preview", null).json();

        assertThat(preview.get("quotaRemaining").isNull()).isTrue();
        assertThat(preview.get("wouldSkipForQuota").asInt()).isZero();
    }

    // ---- preview and test send --------------------------------------------------------------------------------------------

    @Test
    void previewShowsWhoWouldGetItAndQueuesNothing() {
        memberWithMail("Yes", true);
        memberWithMail("Declined", false);
        member(sessionA, "No address", null, true);
        UUID id = draft(Map.of("sendEmail", true, "body", "<p>Hi</p><script>x()</script>"));
        long before = count("select count(*) from email_outbox where community_id = ?", communityA.getId());

        JsonNode p = call(sessionA, "GET", N + "/" + id + "/preview", null).json();

        assertThat(p.get("bodyHtml").asString()).isEqualTo("<p>Hi</p>");
        assertThat(p.get("audienceSize").asInt()).isEqualTo(3);
        assertThat(p.get("eligibleForEmail").asInt()).isEqualTo(1);
        assertThat(p.get("noConsent").asInt()).isEqualTo(1);
        assertThat(p.get("noEmail").asInt()).isEqualTo(1);
        assertThat(count("select count(*) from email_outbox where community_id = ?", communityA.getId())).isEqualTo(before);
        assertThat(status(id)).isEqualTo("DRAFT");
    }

    @Test
    void sendTestMailsOnlyTheAdminWhoAsked() {
        memberWithMail("Not a recipient", true);
        UUID id = draft(Map.of("sendEmail", true));

        ApiClient.Response r = call(sessionA, "POST", N + "/" + id + "/send-test", null);

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().get("queued").asBoolean()).isTrue();
        assertThat(r.json().get("sentTo").asString()).startsWith(adminA.email().substring(0, 1) + "***");
        List<Map<String, Object>> mails = outbox(adminA.email(), "member-announcement");
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).get("payload").toString()).contains("\"test\": true");
        assertThat(mailsFor(id)).as("no member got anything").isEqualTo(1);
        assertThat(status(id)).as("still a draft").isEqualTo("DRAFT");
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'ANNOUNCEMENT_TEST_SENT'", communityA.getId())).isEqualTo(1);
    }

    @Test
    void sendTestRespectsTheQuota() {
        UUID id = draft(Map.of());
        long queued = count("select count(*) from email_outbox where community_id = ?", communityA.getId());
        Plan none = data.customPlan("No emails left", Map.of("emails_per_month", queued), Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", none.getId(), communityA.getId());

        ApiClient.Response r = call(sessionA, "POST", N + "/" + id + "/send-test", null);

        assertThat(r.status()).isEqualTo(402);
        assertThat(outbox(adminA.email(), "member-announcement")).isEmpty();
    }

    // ---- scheduled dispatch -----------------------------------------------------------------------------------------------

    private UUID scheduledDue(Map<String, Object> extra) {
        UUID id = draft(extra);
        call(sessionA, "POST", N + "/" + id + "/schedule", mapOf("scheduledAt", Instant.now().plus(1, ChronoUnit.HOURS).toString()));
        jdbc.update("update announcements set scheduled_at = now() - interval '1 minute' where id = ?", id);
        return id;
    }

    @Test
    void theScheduleSendsADueAnnouncementOnce() {
        memberWithMail("Scheduled reader", true);
        UUID id = scheduledDue(Map.of("sendEmail", true));

        AnnouncementDispatchService.Report first = dispatch.dispatchDue(Instant.now());
        AnnouncementDispatchService.Report second = dispatch.dispatchDue(Instant.now());

        assertThat(first.sent()).isGreaterThanOrEqualTo(1);
        assertThat(second.sent()).isZero();
        assertThat(status(id)).isEqualTo("SENT");
        assertThat(mailsFor(id)).isEqualTo(1);
        JsonNode view = call(sessionA, "GET", N + "/" + id, null).json();
        assertThat(view.get("delivery").get("emailsQueued").asInt()).isEqualTo(1);
        assertThat(view.get("sentAt").isNull()).isFalse();
        Map<String, Object> audit = jdbc.queryForMap("select * from audit_logs where community_id = ? and action = 'ANNOUNCEMENT_SENT'", communityA.getId());
        assertThat(audit.get("after").toString()).contains("schedule");
    }

    @Test
    void notYetDueAndUnscheduledAnnouncementsAreLeftAlone() {
        memberWithMail("Waiting", true);
        UUID later = draft(Map.of("sendEmail", true, "scheduledAt", Instant.now().plus(3, ChronoUnit.HOURS).toString()));
        UUID backToDraft = scheduledDue(Map.of("sendEmail", true));
        call(sessionA, "POST", N + "/" + backToDraft + "/unschedule", null);

        dispatch.dispatchDue(Instant.now());

        assertThat(status(later)).isEqualTo("SCHEDULED");
        assertThat(status(backToDraft)).isEqualTo("DRAFT");
        assertThat(mailsFor(later) + mailsFor(backToDraft)).isZero();
    }

    @Test
    void twoRunsAtOnceStillSendOnce() throws Exception {
        for (int i = 0; i < 3; i++) memberWithMail("Parallel " + i, true);
        UUID id = scheduledDue(Map.of("sendEmail", true));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Callable<Object>> runs = new ArrayList<>();
            for (int i = 0; i < 3; i++) runs.add(() -> dispatch.dispatchDue(Instant.now()));
            for (Future<Object> f : pool.invokeAll(runs)) f.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(mailsFor(id)).isEqualTo(3);
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'ANNOUNCEMENT_SENT'", communityA.getId())).isEqualTo(1);
    }

    @Test
    void aSuspendedCommunitysAnnouncementWaits() {
        memberWithMail("Suspended reader", true);
        UUID id = scheduledDue(Map.of("sendEmail", true));
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());
        try {
            dispatch.dispatchDue(Instant.now());
            assertThat(status(id)).isEqualTo("SCHEDULED");
            assertThat(mailsFor(id)).isZero();
        } finally {
            jdbc.update("update communities set status = 'ACTIVE' where id = ?", communityA.getId());
        }
        dispatch.dispatchDue(Instant.now());
        assertThat(status(id)).as("goes out once the community is active again").isEqualTo("SENT");
    }

    @Test
    void oneBrokenAnnouncementDoesNotStopTheOthers() {
        memberWithMail("Resilient", true);
        UUID bad = scheduledDue(Map.of("sendEmail", true));
        jdbc.update("update announcements set audience = 'GROUP', audience_filter = '{\"group\": null}'::jsonb where id = ?", bad);
        jdbc.update("update announcements set audience_filter = '{\"group\": 5}'::jsonb where id = ?", bad);
        UUID good = scheduledDue(Map.of("sendEmail", true));
        jdbc.update("update announcements set audience = 'SELECTED', audience_filter = '{\"memberIds\": [\"not-a-uuid\"]}'::jsonb where id = ?", bad);

        AnnouncementDispatchService.Report report = dispatch.dispatchDue(Instant.now());

        assertThat(report.failed()).isGreaterThanOrEqualTo(1);
        assertThat(status(good)).isEqualTo("SENT");
        assertThat(status(bad)).as("left for the next run").isEqualTo("SCHEDULED");
        assertThat(mailsFor(bad)).isZero();
    }

    @Test
    void theJobIsScheduledEveryMinuteUnderAShedLock() throws Exception {
        var scheduled = AnnouncementJobs.class.getMethod("dispatchDue").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        var lock = AnnouncementJobs.class.getMethod("dispatchDue").getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);
        assertThat(scheduled.cron()).isEqualTo("0 * * * * *");
        assertThat(lock.name()).isEqualTo("announcementDispatchJob");
        UUID id = scheduledDue(Map.of());
        jobs.dispatchDue();
        assertThat(status(id)).isIn("SENT", "SCHEDULED"); // runs under the lock; another test class may hold it
    }

    // ---- tenant isolation -------------------------------------------------------------------------------------------------

    @Test
    void isolationOfEveryEndpoint() {
        UUID b = id(create(sessionB, Map.of()).json());
        String before = jdbc.queryForObject("select title || status || body from announcements where id = ?", String.class, b);
        Runnable unchanged = () -> assertThat(jdbc.queryForObject("select title || status || body from announcements where id = ?", String.class, b)).isEqualTo(before);

        assertListHides(N, b);
        assertCrossTenantRead(N + "/" + b);
        assertCrossTenantRead(N + "/" + b + "/preview");
        assertCrossTenantUpdate("PATCH", N + "/" + b, mapOf("title", "Hijacked"), unchanged);
        UUID b2 = id(create(sessionB, Map.of()).json());
        assertCrossTenantUpdate("POST", N + "/" + b2 + "/schedule", mapOf("scheduledAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()), () -> assertThat(status(b2)).isEqualTo("DRAFT"));
        UUID b3 = id(create(sessionB, Map.of("scheduledAt", Instant.now().plus(1, ChronoUnit.DAYS).toString())).json());
        assertCrossTenantUpdate("POST", N + "/" + b3 + "/unschedule", null, () -> assertThat(status(b3)).isEqualTo("SCHEDULED"));
        UUID b4 = id(create(sessionB, Map.of("sendEmail", true)).json());
        member(sessionB, "B reader", email(), true);
        assertCrossTenantUpdate("POST", N + "/" + b4 + "/send", null, () -> {
            assertThat(status(b4)).isEqualTo("DRAFT");
            assertThat(mailsFor(b4)).isZero();
        });
        UUID b5 = id(create(sessionB, Map.of()).json());
        long mails = count("select count(*) from email_outbox where community_id = ?", communityB.getId());
        assertCrossTenantUpdate("POST", N + "/" + b5 + "/send-test", null, () -> assertThat(count("select count(*) from email_outbox where community_id = ?", communityB.getId())).isEqualTo(mails));
        assertCreateCannotTargetOtherTenant(N, mapOf("title", "Mine", "body", "<p>x</p>"), r -> id(r.json()), N + "/%s");
    }

    @Test
    void aMemberOfAnotherCommunityCannotBeAddressed() {
        UUID theirs = member(sessionB, "Theirs", email(), true);
        UUID id = draft(Map.of());

        ApiClient.Response r = call(sessionA, "PATCH", N + "/" + id, mapOf("audience", "SELECTED", "memberIds", List.of(theirs.toString())));

        assertThat(r.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select audience from announcements where id = ?", String.class, id)).isEqualTo("ALL_ACTIVE");
    }

    @Test
    void platformAnnouncementsAreNotReachableThroughTheCommunityEndpoints() {
        ApiClient.Response platform = asSuper("POST", ADMIN + "/announcements", mapOf("title", "Offer", "body", "<p>Half price</p>", "kind", "OFFER"));
        assertThat(platform.status()).as(platform.body()).isEqualTo(201);
        UUID p = id(platform.json());

        assertThat(call(sessionA, "GET", N + "/" + p, null).status()).isEqualTo(404);
        assertThat(call(sessionA, "PATCH", N + "/" + p, mapOf("title", "x")).status()).isEqualTo(404);
        assertThat(call(sessionA, "POST", N + "/" + p + "/send", null).status()).isEqualTo(404);
        assertThat(call(sessionA, "GET", N, null).body()).doesNotContain(p.toString());
        assertThat(status(p)).isEqualTo("DRAFT");
    }

    @Test
    void aSuspendedCommunityCannotSend() {
        UUID id = draft(Map.of());
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());
        try {
            assertThat(send(id).status()).isEqualTo(403);
            assertThat(create(sessionA, Map.of()).status()).isEqualTo(403);
            assertThat(call(sessionA, "GET", N + "/" + id, null).status()).as("still readable").isEqualTo(200);
        } finally {
            jdbc.update("update communities set status = 'ACTIVE' where id = ?", communityA.getId());
        }
        assertThat(status(id)).isEqualTo("DRAFT");
    }
}

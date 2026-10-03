package com.amanahconnect.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractDeskIT;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SupportChatIT extends AbstractDeskIT {

    private static final String S = "/api/v1/community/support";
    private static final String A = "/api/v1/admin/support";

    private ApiClient.Response startThread(Session s, String subject, String body) {
        return call(s, "POST", S + "/threads", mapOf("subject", subject, "body", body));
    }

    private UUID threadA(String subject) {
        ApiClient.Response r = startThread(sessionA, subject, "Hello, we need help with " + subject);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return id(r.json());
    }

    private ApiClient.Response say(Session s, UUID thread, String body) {
        return call(s, "POST", S + "/threads/" + thread + "/messages", mapOf("body", body));
    }

    private ApiClient.Response reply(UUID thread, String body) {
        return asSuper("POST", A + "/threads/" + thread + "/messages", mapOf("body", body));
    }

    private JsonNode thread(Session s, UUID id) {
        return call(s, "GET", S + "/threads/" + id, null).json();
    }

    private JsonNode platformThread(UUID id) {
        return asSuper("GET", A + "/threads/" + id, null).json();
    }

    private long supportEmailsTo(String address) {
        return count("select count(*) from email_outbox where to_email = ? and template = 'support-message'", address);
    }

    private List<String> ids(ApiClient.Response r) {
        List<String> out = new ArrayList<>();
        r.json().get("items").forEach(i -> out.add(i.get("id").asString()));
        return out;
    }

    // ---- conversation basics --------------------------------------------------------------------------------------------

    @Test
    void aCommunityStartsAThreadWithAFirstMessage() {
        ApiClient.Response r = startThread(sessionA, "  Cannot send bills  ", "Bills are not going out.");

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        JsonNode t = r.json();
        assertThat(t.get("subject").asString()).isEqualTo("Cannot send bills");
        assertThat(t.get("status").asString()).isEqualTo("OPEN");
        assertThat(t.get("priority").asString()).isEqualTo("MEDIUM");
        assertThat(t.get("messageCount").asLong()).isEqualTo(1);
        assertThat(t.get("unreadCount").asLong()).as("my own message is not unread for me").isZero();
        assertThat(t.get("communityId").asString()).isEqualTo(communityA.getId().toString());
        JsonNode page = call(sessionA, "GET", S + "/threads/" + id(t) + "/messages", null).json();
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("seq").asLong()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("side").asString()).isEqualTo("COMMUNITY");
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'SUPPORT_THREAD_CREATED'", communityA.getId())).isEqualTo(1);
    }

    @Test
    void rejectsBadInput() {
        assertThat(startThread(sessionA, "", "x").status()).isEqualTo(400);
        assertThat(startThread(sessionA, "x", " ").status()).isEqualTo(400);
        assertThat(call(sessionA, "POST", S + "/threads", mapOf("subject", "x", "body", "y", "priority", "NOW")).status()).isEqualTo(400);
        assertThat(startThread(sessionA, "x", "y".repeat(5001)).status()).isEqualTo(400);
        UUID t = threadA("input");
        assertThat(say(sessionA, t, "").status()).isEqualTo(400);
        assertThat(call(sessionA, "GET", S + "/threads/" + t + "/messages?after=-1", null).status()).isEqualTo(400);
        assertThat(call(sessionA, "GET", S + "/threads/" + t + "/messages?limit=0", null).status()).isEqualTo(400);
        assertThat(call(sessionA, "GET", S + "/threads/" + t + "/messages?limit=201", null).status()).isEqualTo(400);
    }

    @Test
    void everyAdminOfTheCommunitySeesItsThreads() {
        UUID t = threadA("shared");
        Session colleague = loginOk(users.extraAdminOf(communityA));

        assertThat(ids(call(colleague, "GET", S + "/threads", null))).containsExactly(t.toString());
        assertThat(say(colleague, t, "I am on it too").status()).isEqualTo(201);
    }

    // ---- visibility rules ---------------------------------------------------------------------------------------------------

    @Test
    void aCommunityNeverSeesAnotherCommunitysThreads() {
        UUID b = id(startThread(sessionB, "B's problem", "secret B detail").json());
        UUID a = threadA("A's problem");

        assertThat(ids(call(sessionA, "GET", S + "/threads", null))).containsExactly(a.toString());
        assertListHides(S + "/threads", b);
        assertCrossTenantRead(S + "/threads/" + b);
        assertCrossTenantRead(S + "/threads/" + b + "/messages");
        long before = count("select count(*) from support_messages where community_id = ?", communityB.getId());
        assertCrossTenantUpdate("POST", S + "/threads/" + b + "/messages", mapOf("body", "intruder"),
                () -> assertThat(count("select count(*) from support_messages where community_id = ?", communityB.getId())).isEqualTo(before));
        UUID b2 = id(startThread(sessionB, "second", "x").json());
        assertCrossTenantUpdate("POST", S + "/threads/" + b2 + "/read", null, () -> {});
        assertCreateCannotTargetOtherTenant(S + "/threads", mapOf("subject", "Mine", "body", "x"), r -> id(r.json()), S + "/threads/%s");
        assertTenantSingleton("GET", S + "/summary", null, () -> call(sessionB, "GET", S + "/summary", null).body());
        assertTenantSingleton("POST", S + "/attachments/upload-url", mapOf("contentType", "image/png", "sizeBytes", 100), () -> call(sessionB, "GET", S + "/summary", null).body());
        assertThat(call(sessionA, "GET", S + "/summary", null).body()).doesNotContain(b.toString());
    }

    @Test
    void theTwoSidesOnlyReachTheirOwnEndpoints() {
        UUID t = threadA("boundary");

        assertThat(asSuper("GET", S + "/threads", null).status()).as("a super admin has no community").isIn(401, 403);
        assertThat(call(sessionA, "GET", A + "/threads", null).status()).isEqualTo(403);
        assertThat(call(sessionA, "GET", A + "/threads/" + t, null).status()).isEqualTo(403);
        assertThat(call(sessionA, "PATCH", A + "/threads/" + t, mapOf("status", "CLOSED")).status()).isEqualTo(403);
        assertThat(call(sessionA, "POST", A + "/threads/" + t + "/messages", mapOf("body", "pretend to be support")).status()).isEqualTo(403);
        assertThat(api.call("GET", A + "/threads", null).status()).isEqualTo(401);
        assertThat(api.call("GET", S + "/threads", null).status()).isEqualTo(401);
        assertThat(count("select count(*) from support_messages where thread_id = ? and sender_side = 'PLATFORM'", t)).isZero();
    }

    @Test
    void theCommunitySideCannotChangeStatusPriorityOrAssignment() {
        UUID t = threadA("no patch");
        assertThat(call(sessionA, "PATCH", S + "/threads/" + t, mapOf("status", "CLOSED")).status()).isIn(404, 405);
        assertThat(thread(sessionA, t).get("status").asString()).isEqualTo("OPEN");
    }

    @Test
    void theSupportTeamSeesAndFiltersEveryCommunity() {
        UUID a = threadA("Alpha billing");
        UUID b = id(startThread(sessionB, "Beta import", "x").json());
        reply(a, "On it");

        List<String> all = ids(asSuper("GET", A + "/threads?size=100", null));
        assertThat(all).contains(a.toString(), b.toString());
        assertThat(ids(asSuper("GET", A + "/threads?communityId=" + communityA.getId(), null))).containsExactly(a.toString());
        assertThat(ids(asSuper("GET", A + "/threads?communityId=" + communityB.getId() + "&status=OPEN", null))).containsExactly(b.toString());
        assertThat(ids(asSuper("GET", A + "/threads?communityId=" + communityA.getId() + "&status=WAITING", null))).containsExactly(a.toString());
        assertThat(ids(asSuper("GET", A + "/threads?q=beta&communityId=" + communityB.getId(), null))).containsExactly(b.toString());
        assertThat(ids(asSuper("GET", A + "/threads?q=" + communityA.getName().substring(0, 6).replace(" ", "%20") + "&communityId=" + communityA.getId(), null))).as("by community name").containsExactly(a.toString());
        assertThat(ids(asSuper("GET", A + "/threads?communityId=" + communityA.getId() + "&unassigned=true", null))).containsExactly(a.toString());
        assertThat(asSuper("GET", A + "/threads?sort=body", null).status()).isEqualTo(400);
        assertThat(platformThread(b).get("communityName").asString()).isEqualTo(communityB.getName());
    }

    // ---- statuses --------------------------------------------------------------------------------------------------------

    @Test
    void statusesFollowWhoSpokeLast() {
        UUID t = threadA("status flow");
        assertThat(platformThread(t).get("status").asString()).isEqualTo("OPEN");

        reply(t, "Looking into it");
        assertThat(thread(sessionA, t).get("status").asString()).isEqualTo("WAITING");

        say(sessionA, t, "Thanks, any news?");
        assertThat(thread(sessionA, t).get("status").asString()).isEqualTo("OPEN");

        assertThat(asSuper("PATCH", A + "/threads/" + t, mapOf("status", "RESOLVED")).status()).isEqualTo(200);
        assertThat(say(sessionA, t, "Actually it is back").status()).isEqualTo(201);
        assertThat(thread(sessionA, t).get("status").asString()).as("a customer message reopens a resolved thread").isEqualTo("OPEN");
    }

    @Test
    void aClosedThreadTakesNoMoreMessagesFromEitherSide() {
        UUID t = threadA("to close");
        ApiClient.Response closed = asSuper("PATCH", A + "/threads/" + t, mapOf("status", "CLOSED"));
        assertThat(closed.json().get("closedAt").isNull()).isFalse();

        ApiClient.Response fromCommunity = say(sessionA, t, "one more thing");
        ApiClient.Response fromPlatform = reply(t, "anything else?");

        assertThat(fromCommunity.status()).isEqualTo(409);
        assertThat(fromCommunity.code()).isEqualTo("THREAD_CLOSED");
        assertThat(fromPlatform.status()).isEqualTo(409);
        assertThat(asSuper("PATCH", A + "/threads/" + t, mapOf("status", "OPEN")).json().get("closedAt").isNull()).as("reopened by the support team").isTrue();
        assertThat(say(sessionA, t, "thanks").status()).isEqualTo(201);
    }

    @Test
    void assigningAndPrioritising() {
        UUID t = threadA("assign me");
        AuthTestUsers.TestUser other = users.superAdmin();

        JsonNode assigned = asSuper("PATCH", A + "/threads/" + t, mapOf("assignedTo", other.id().toString(), "priority", "URGENT")).json();

        assertThat(assigned.get("assignedTo").asString()).isEqualTo(other.id().toString());
        assertThat(assigned.get("assignedToName").asString()).isNotBlank();
        assertThat(assigned.get("priority").asString()).isEqualTo("URGENT");
        assertThat(ids(asSuper("GET", A + "/threads?assignedTo=" + other.id(), null))).containsExactly(t.toString());
        assertThat(ids(asSuper("GET", A + "/threads?mine=true&communityId=" + communityA.getId(), null))).isEmpty();
        assertThat(asSuper("PATCH", A + "/threads/" + t, mapOf("unassign", true)).json().get("assignedTo").isNull()).isTrue();
        assertThat(asSuper("PATCH", A + "/threads/" + t, mapOf("assignedTo", adminA.id().toString())).status()).as("a community admin cannot be the assignee").isEqualTo(400);
        assertThat(asSuper("PATCH", A + "/threads/" + t, mapOf("assignedTo", UUID.randomUUID().toString())).status()).isEqualTo(400);
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'SUPPORT_THREAD_UPDATED'", communityA.getId())).isEqualTo(2);
        assertThat(asSuper("PATCH", A + "/threads/" + UUID.randomUUID(), mapOf("status", "OPEN")).status()).isEqualTo(404);
    }

    @Test
    void theDeskCounts() {
        UUID open = threadA("c1");
        UUID waiting = threadA("c2");
        reply(waiting, "hi");
        JsonNode before = asSuper("GET", A + "/counts", null).json();
        UUID closed = threadA("c3");
        asSuper("PATCH", A + "/threads/" + closed, mapOf("status", "CLOSED"));

        JsonNode after = asSuper("GET", A + "/counts", null).json();

        assertThat(after.get("total").asLong()).isEqualTo(before.get("total").asLong() + 1);
        assertThat(after.get("closed").asLong()).isEqualTo(before.get("closed").asLong() + 1);
        assertThat(after.get("open").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(after.get("waiting").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(open).isNotEqualTo(waiting);
    }

    // ---- unread counters ---------------------------------------------------------------------------------------------------

    @Test
    void unreadCountersForBothSides() {
        UUID t = threadA("unread");
        assertThat(platformThread(t).get("unreadCount").asLong()).as("the first message is unread by support").isEqualTo(1);
        assertThat(asSuper("GET", A + "/threads?unread=true&communityId=" + communityA.getId(), null).json().get("items")).hasSize(1);

        reply(t, "one");
        reply(t, "two");
        say(sessionA, t, "ack");

        JsonNode mine = thread(sessionA, t);
        assertThat(mine.get("unreadCount").asLong()).isEqualTo(2);
        JsonNode summary = call(sessionA, "GET", S + "/summary", null).json();
        assertThat(summary.get("unreadMessages").asLong()).isEqualTo(2);
        assertThat(summary.get("unreadThreads").asLong()).isEqualTo(1);
        assertThat(platformThread(t).get("unreadCount").asLong()).as("support has 2 of mine unread").isEqualTo(2);

        assertThat(call(sessionA, "GET", S + "/threads/" + t + "/messages", null).status()).isEqualTo(200);
        assertThat(thread(sessionA, t).get("unreadCount").asLong()).as("reading the list is not reading the thread").isEqualTo(2);

        JsonNode afterRead = call(sessionA, "POST", S + "/threads/" + t + "/read", null).json();
        assertThat(afterRead.get("unreadMessages").asLong()).isZero();
        assertThat(thread(sessionA, t).get("unreadCount").asLong()).isZero();
        assertThat(platformThread(t).get("unreadCount").asLong()).as("my own unread for support is untouched").isEqualTo(2);

        assertThat(asSuper("POST", A + "/threads/" + t + "/read", null).status()).isEqualTo(200);
        assertThat(platformThread(t).get("unreadCount").asLong()).isZero();
        JsonNode messages = call(sessionA, "GET", S + "/threads/" + t + "/messages", null).json().get("items");
        assertThat(messages.get(1).get("readAt").isNull()).as("a message I wrote is read once the other side reads").isFalse();
    }

    @Test
    void markingReadUpToASeq() {
        UUID t = threadA("partial");
        reply(t, "1");
        reply(t, "2");
        reply(t, "3");

        call(sessionA, "POST", S + "/threads/" + t + "/read", mapOf("upToSeq", 3));

        assertThat(thread(sessionA, t).get("unreadCount").asLong()).as("seq 2 and 3 read, seq 4 not").isEqualTo(1);
    }

    @Test
    void readReceiptsAreAuditedOnlyWhenSomethingChanged() {
        UUID t = threadA("audit read");
        reply(t, "hello");
        call(sessionA, "POST", S + "/threads/" + t + "/read", null);
        call(sessionA, "POST", S + "/threads/" + t + "/read", null);
        call(sessionA, "POST", S + "/threads/" + t + "/read", null);

        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'SUPPORT_THREAD_READ'", communityA.getId())).isEqualTo(1);
    }

    // ---- polling cursor ----------------------------------------------------------------------------------------------------

    @Test
    void theSinceCursorOnlyReturnsWhatIsNew() {
        UUID t = threadA("polling");
        JsonNode first = call(sessionA, "GET", S + "/threads/" + t + "/messages?after=0", null).json();
        long cursor = first.get("cursor").asLong();
        assertThat(cursor).isEqualTo(1);

        JsonNode nothing = call(sessionA, "GET", S + "/threads/" + t + "/messages?after=" + cursor, null).json();
        assertThat(nothing.get("items")).isEmpty();
        assertThat(nothing.get("cursor").asLong()).as("an empty poll keeps the cursor").isEqualTo(cursor);
        assertThat(nothing.get("hasMore").asBoolean()).isFalse();

        reply(t, "second");
        reply(t, "third");
        JsonNode news = call(sessionA, "GET", S + "/threads/" + t + "/messages?after=" + cursor, null).json();

        assertThat(news.get("items")).hasSize(2);
        assertThat(news.get("items").get(0).get("body").asString()).isEqualTo("second");
        assertThat(news.get("cursor").asLong()).isEqualTo(3);
        assertThat(news.get("thread").get("status").asString()).isEqualTo("WAITING");
        assertThat(news.get("thread").get("messageCount").asLong()).isEqualTo(3);
    }

    @Test
    void pagesThroughALongConversation() {
        UUID t = threadA("long");
        for (int i = 0; i < 6; i++) reply(t, "m" + i);

        JsonNode p1 = call(sessionA, "GET", S + "/threads/" + t + "/messages?limit=3", null).json();
        JsonNode p2 = call(sessionA, "GET", S + "/threads/" + t + "/messages?limit=3&after=" + p1.get("cursor").asLong(), null).json();
        JsonNode p3 = call(sessionA, "GET", S + "/threads/" + t + "/messages?limit=3&after=" + p2.get("cursor").asLong(), null).json();

        assertThat(p1.get("items")).hasSize(3);
        assertThat(p1.get("hasMore").asBoolean()).isTrue();
        assertThat(p2.get("items")).hasSize(3);
        assertThat(p2.get("hasMore").asBoolean()).isTrue();
        assertThat(p3.get("items")).hasSize(1);
        assertThat(p3.get("hasMore").asBoolean()).isFalse();
        assertThat(p3.get("cursor").asLong()).isEqualTo(7);
    }

    @Test
    void theSummaryShowsWhereEachLiveThreadIs() {
        UUID t = threadA("summary");
        UUID closed = threadA("done");
        asSuper("PATCH", A + "/threads/" + closed, mapOf("status", "CLOSED"));
        reply(t, "a");

        JsonNode threads = call(sessionA, "GET", S + "/summary", null).json().get("threads");

        assertThat(threads).hasSize(1);
        assertThat(threads.get(0).get("id").asString()).isEqualTo(t.toString());
        assertThat(threads.get(0).get("messageSeq").asLong()).isEqualTo(2);
        assertThat(threads.get(0).get("unreadCount").asLong()).isEqualTo(1);
    }

    @Test
    void messageNumbersAreGaplessUnderConcurrentPosting() throws Exception {
        UUID t = threadA("race");
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                jobs.add(() -> (k % 2 == 0 ? say(sessionA, t, "c" + k) : reply(t, "p" + k)).status());
            }
            for (Future<Integer> f : pool.invokeAll(jobs)) assertThat(f.get()).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }

        List<Long> seqs = jdbc.queryForList("select seq from support_messages where thread_id = ? order by seq", Long.class, t);
        assertThat(seqs).hasSize(n + 1);
        for (int i = 0; i < seqs.size(); i++) assertThat(seqs.get(i)).isEqualTo(i + 1L);
        assertThat(jdbc.queryForObject("select message_seq from support_threads where id = ?", Long.class, t)).isEqualTo(n + 1L);
    }

    // ---- email notification ------------------------------------------------------------------------------------------------

    @Test
    void aBurstOfMessagesSendsOneEmailToTheSupportTeam() {
        long before = supportEmailsTo(superAdmin.email());
        UUID t = threadA("burst");
        for (int i = 0; i < 4; i++) say(sessionA, t, "more " + i);

        assertThat(supportEmailsTo(superAdmin.email()) - before).as("5 messages, one email").isEqualTo(1);
        Map<String, Object> mail = jdbc.queryForMap("select * from email_outbox where to_email = ? and template = 'support-message' order by created_at desc limit 1", superAdmin.email());
        assertThat(mail.get("community_id")).as("platform mail does not use a community's quota").isNull();
        assertThat(mail.get("payload").toString()).contains(communityA.getName(), "burst");
    }

    @Test
    void theNextMessageAfterTheOtherSideReadsEmailsAgain() {
        long before = supportEmailsTo(superAdmin.email());
        UUID t = threadA("again");
        say(sessionA, t, "second");
        asSuper("POST", A + "/threads/" + t + "/read", null);

        say(sessionA, t, "third, after support read the others");

        assertThat(supportEmailsTo(superAdmin.email()) - before).isEqualTo(2);
    }

    @Test
    void unreadMessagesAreReMailedAfterTheReminderWindow() {
        long before = supportEmailsTo(superAdmin.email());
        UUID t = threadA("reminder");
        say(sessionA, t, "still waiting");
        assertThat(supportEmailsTo(superAdmin.email()) - before).isEqualTo(1);

        jdbc.update("update support_threads set platform_notified_at = now() - interval '31 minutes' where id = ?", t);
        say(sessionA, t, "hello???");

        assertThat(supportEmailsTo(superAdmin.email()) - before).isEqualTo(2);
    }

    @Test
    void theSupportReplyEmailsEveryAdminOfTheCommunityOncePerBurst() {
        UUID t = threadA("reply mail");
        AuthTestUsers.TestUser colleague = users.extraAdminOf(communityA);

        reply(t, "one");
        reply(t, "two");
        reply(t, "three");

        assertThat(supportEmailsTo(adminA.email())).isEqualTo(1);
        assertThat(supportEmailsTo(colleague.email())).isEqualTo(1);
        assertThat(supportEmailsTo(adminB.email())).as("another community hears nothing").isZero();
    }

    @Test
    void theAssignedSuperAdminIsTheOneEmailed() {
        AuthTestUsers.TestUser assignee = users.superAdmin();
        UUID t = threadA("assigned mail");
        long first = supportEmailsTo(assignee.email());
        asSuper("PATCH", A + "/threads/" + t, mapOf("assignedTo", assignee.id().toString()));
        asSuper("POST", A + "/threads/" + t + "/read", null);
        long others = supportEmailsTo(superAdmin.email());

        say(sessionA, t, "for the assignee only");

        assertThat(supportEmailsTo(assignee.email()) - first).isEqualTo(1);
        assertThat(supportEmailsTo(superAdmin.email())).as("not broadcast when someone owns the thread").isEqualTo(others);
    }

    // ---- attachments -------------------------------------------------------------------------------------------------------

    private JsonNode uploadUrl(Map<String, Object> body) {
        ApiClient.Response r = call(sessionA, "POST", S + "/attachments/upload-url", body);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }

    @Test
    void uploadUrlsAreValidated() {
        assertThat(call(sessionA, "POST", S + "/attachments/upload-url", mapOf("contentType", "text/html", "sizeBytes", 100)).status()).isEqualTo(400);
        assertThat(call(sessionA, "POST", S + "/attachments/upload-url", mapOf("contentType", "image/svg+xml", "sizeBytes", 100)).status()).isEqualTo(400);
        assertThat(call(sessionA, "POST", S + "/attachments/upload-url", mapOf("contentType", "image/png", "sizeBytes", 0)).status()).isEqualTo(400);
        assertThat(call(sessionA, "POST", S + "/attachments/upload-url", mapOf("contentType", "image/png", "sizeBytes", 5 * 1024 * 1024 + 1)).status()).isEqualTo(400);

        JsonNode ok = uploadUrl(mapOf("contentType", "image/png", "sizeBytes", 2048));
        assertThat(ok.get("attachmentKey").asString()).startsWith("communities/" + communityA.getId() + "/support/").endsWith(".png");
        assertThat(storage.signedSizes.get(ok.get("attachmentKey").asString())).isEqualTo(2048L);
    }

    @Test
    void aMessageCanCarryAnUploadedAttachmentBothSidesCanOpen() {
        UUID t = threadA("with file");
        String key = uploadUrl(mapOf("contentType", "application/pdf", "sizeBytes", 4000)).get("attachmentKey").asString();
        storage.put(key, "application/pdf", 4000);

        ApiClient.Response sent = call(sessionA, "POST", S + "/threads/" + t + "/messages", mapOf("body", "see attached", "attachmentKey", key, "attachmentName", "..\\invoice/march.pdf"));

        assertThat(sent.status()).as(sent.body()).isEqualTo(201);
        JsonNode attachment = sent.json().get("attachment");
        assertThat(attachment.get("name").asString()).as("path separators are neutralised").isEqualTo(".._invoice_march.pdf");
        assertThat(attachment.get("contentType").asString()).isEqualTo("application/pdf");
        assertThat(attachment.get("size").asLong()).isEqualTo(4000);
        assertThat(attachment.get("downloadUrl").asString()).isNotBlank();
        JsonNode viaSupport = asSuper("GET", A + "/threads/" + t + "/messages", null).json().get("items");
        assertThat(viaSupport.get(1).get("attachment").get("downloadUrl").asString()).isNotBlank();
    }

    @Test
    void supportCanAttachFilesToo() {
        UUID t = threadA("support file");
        ApiClient.Response signed = asSuper("POST", A + "/threads/" + t + "/attachments/upload-url", mapOf("contentType", "image/jpeg", "sizeBytes", 900));
        assertThat(signed.status()).as(signed.body()).isEqualTo(200);
        String key = signed.json().get("attachmentKey").asString();
        assertThat(key).startsWith("communities/" + communityA.getId() + "/support/");
        storage.put(key, "image/jpeg", 900);

        ApiClient.Response sent = asSuper("POST", A + "/threads/" + t + "/messages", mapOf("body", "screenshot of the fix", "attachmentKey", key));

        assertThat(sent.status()).as(sent.body()).isEqualTo(201);
        assertThat(sent.json().get("attachment").get("name").asString()).as("a default name").isEqualTo("attachment.jpg");
    }

    private ApiClient.Response sayWithFile(UUID thread, String key) {
        return call(sessionA, "POST", S + "/threads/" + thread + "/messages", mapOf("body", "file", "attachmentKey", key));
    }

    @Test
    void rejectsAttachmentsThatAreNotWhatTheyClaim() {
        UUID t = threadA("bad files");
        String prefix = "communities/" + communityA.getId() + "/support/";

        String notUploaded = uploadUrl(mapOf("contentType", "image/png", "sizeBytes", 100)).get("attachmentKey").asString();
        assertThat(sayWithFile(t, notUploaded).status()).as("never uploaded").isEqualTo(400);

        String wrongType = prefix + UUID.randomUUID() + ".png";
        storage.put(wrongType, "text/html", 100);
        assertThat(sayWithFile(t, wrongType).status()).as("stored as HTML").isEqualTo(400);
        assertThat(storage.has(wrongType)).as("an unacceptable upload is removed").isFalse();

        String tooBig = prefix + UUID.randomUUID() + ".pdf";
        storage.put(tooBig, "application/pdf", 6L * 1024 * 1024);
        assertThat(sayWithFile(t, tooBig).status()).as("over 5 MB").isEqualTo(400);

        String foreign = "communities/" + communityB.getId() + "/support/" + UUID.randomUUID() + ".png";
        storage.put(foreign, "image/png", 100);
        assertThat(sayWithFile(t, foreign).status()).as("another community's key").isEqualTo(400);
        assertThat(storage.has(foreign)).as("and it is left alone").isTrue();

        for (String hostile : new String[] {"../../etc/passwd", prefix + "../" + UUID.randomUUID() + ".png", prefix + "x.png", "http://evil.test/a.png", prefix + UUID.randomUUID() + ".svg"}) {
            assertThat(sayWithFile(t, hostile).status()).as(hostile).isEqualTo(400);
        }
        assertThat(count("select count(*) from support_messages where thread_id = ?", t)).as("none of them became a message").isEqualTo(1);
    }

    @Test
    void anAttachmentKeyCanOnlyBeUsedOnce() {
        UUID t = threadA("reuse");
        String key = uploadUrl(mapOf("contentType", "image/png", "sizeBytes", 100)).get("attachmentKey").asString();
        storage.put(key, "image/png", 100);

        assertThat(sayWithFile(t, key).status()).isEqualTo(201);
        assertThat(sayWithFile(t, key).status()).isEqualTo(400);
    }

    @Test
    void aFailedAttachmentCheckLeavesNoHalfSentMessage() {
        UUID t = threadA("atomic");
        String bad = "communities/" + communityA.getId() + "/support/" + UUID.randomUUID() + ".png";
        long before = count("select count(*) from support_messages where thread_id = ?", t);
        long seq = jdbc.queryForObject("select message_seq from support_threads where id = ?", Long.class, t);

        assertThat(sayWithFile(t, bad).status()).isEqualTo(400);

        assertThat(count("select count(*) from support_messages where thread_id = ?", t)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select message_seq from support_threads where id = ?", Long.class, t)).isEqualTo(seq);
    }
}

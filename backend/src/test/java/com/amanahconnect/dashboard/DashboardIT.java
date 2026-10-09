package com.amanahconnect.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractDeskIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.CountingJdbcTemplate;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class DashboardIT extends AbstractDeskIT {

    private static final String D = "/api/v1/community/dashboard";

    @Autowired DashboardService dashboards;

    @BeforeEach
    void freshCache() {
        dashboards.evictAll();
    }

    private JsonNode dashboard(Session s) {
        ApiClient.Response r = call(s, "GET", D, null);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }

    private UUID payer(String name) {
        return member(sessionA, name, email(), true);
    }

    private UUID invoiceWithDue(UUID member, String amount, int dueInDays) {
        return id(invoiceA(member, amount, TODAY.plusDays(dueInDays)));
    }

    private String yearMonth(LocalDate date) {
        return YearMonth.from(date).toString();
    }

    /** The whole story the numbers are checked against. */
    private void seedCommunityA() {
        UUID m1 = payer("Asha");
        UUID m2 = payer("Bina");
        UUID m3 = payer("Chitra");
        UUID m4 = payer("Dev");
        UUID inactive = payer("Inactive Ivan");
        UUID deleted = payer("Deleted Dan");
        jdbc.update("update members set status = 'INACTIVE' where id = ?", inactive);
        jdbc.update("update members set deleted_at = now() where id = ?", deleted);
        jdbc.update("update communities set opening_balance = 1000.00 where id = ?", communityA.getId());

        UUID i1 = invoiceWithDue(m1, "1000.00", 20);
        UUID i2 = invoiceWithDue(m2, "500.00", 5);
        UUID i3 = invoiceWithDue(m3, "300.00", 20);
        jdbc.update("update invoices set due_date = ?, status = 'OVERDUE' where id = ?", TODAY.minusDays(3), i3);
        UUID cancelled = invoiceWithDue(m4, "999.00", 3);
        assertThat(asA("POST", INVOICES + "/" + cancelled + "/cancel", Map.of("reason", "duplicate")).status()).isEqualTo(200);
        assertThat(asA("POST", INVOICES, new LinkedHashMap<>(Map.of("memberId", m4.toString(), "kind", "MAINTENANCE", "description", "Draft", "amount", "111.00", "dueDate", TODAY.plusDays(2).toString(), "draft", true))).status()).isEqualTo(201);
        invoiceWithDue(m4, "250.00", 10);

        payOk(sessionA, i1, "400.00");
        payOk(sessionA, i3, "100.00");
        JsonNode fifty = payOk(sessionA, i2, "50.00");
        ApiClient.Response reversed = asA("POST", PAYMENTS + "/" + id(fifty.has("payment") ? fifty.get("payment") : fifty) + "/reverse", Map.of("reason", "wrong invoice"));
        assertThat(reversed.status()).as(reversed.body()).isEqualTo(201);

        UUID expense = category("Maintenance", "EXPENSE");
        UUID income = category("Other", "INCOME");
        assertThat(asA("POST", LEDGER + "/entries", Map.of("type", "EXPENSE", "categoryId", expense.toString(), "amount", "120.00", "entryDate", TODAY.toString(), "title", "Repairs")).status()).isEqualTo(201);
        assertThat(asA("POST", LEDGER + "/entries", Map.of("type", "INCOME", "categoryId", income.toString(), "amount", "80.00", "entryDate", TODAY.toString(), "title", "Hall hire")).status()).isEqualTo(201);

        UUID urgent = id(asA("POST", "/api/v1/community/complaints", Map.of("subject", "Gas smell", "description", "x", "priority", "URGENT")).json());
        jdbc.update("update complaints set created_at = now() - interval '3 days' where id = ?", urgent);
        asA("POST", "/api/v1/community/complaints", Map.of("subject", "Lift", "description", "x"));
        asA("POST", "/api/v1/community/complaints", Map.of("subject", "Noise", "description", "x"));
        UUID done = id(asA("POST", "/api/v1/community/complaints", Map.of("subject", "Done", "description", "x")).json());
        asA("POST", "/api/v1/community/complaints/" + done + "/status", Map.of("status", "RESOLVED"));

        UUID thread = id(asA("POST", "/api/v1/community/support/threads", Map.of("subject", "Help", "body", "please")).json());
        asSuper("POST", "/api/v1/admin/support/threads/" + thread + "/messages", Map.of("body", "one"));
        asSuper("POST", "/api/v1/admin/support/threads/" + thread + "/messages", Map.of("body", "two"));
    }

    private UUID category(String name, String type) {
        for (JsonNode c : asA("GET", LEDGER + "/categories", null).json()) {
            if (c.get("name").asString().equals(name) && c.get("type").asString().equals(type)) return id(c);
        }
        throw new AssertionError(name);
    }

    // ---- the numbers --------------------------------------------------------------------------------------------------------

    @Test
    void everyHeadlineFigureMatchesWhatWasSeeded() {
        seedCommunityA();

        JsonNode d = dashboard(sessionA);

        assertThat(d.get("currency").asString()).isEqualTo("INR");
        assertThat(d.get("month").asString()).isEqualTo(yearMonth(TODAY));
        assertThat(d.get("members").get("total").asLong()).as("deleted members do not count").isEqualTo(5);
        assertThat(d.get("members").get("active").asLong()).isEqualTo(4);
        JsonNode c = d.get("collection");
        assertThat(c.get("billed").asString()).as("1000 + 500 + 300 + 250, not the cancelled or the draft").isEqualTo("2050.00");
        assertThat(c.get("collected").asString()).as("400 + 100 paid; the 50 was reversed").isEqualTo("500.00");
        assertThat(c.get("outstanding").asString()).isEqualTo("1550.00");
        assertThat(c.get("receivedInMonth").asString()).as("400 + 100 + 50 - 50").isEqualTo("500.00");
        assertThat(d.get("overdue").get("count").asLong()).isEqualTo(1);
        assertThat(d.get("overdue").get("amount").asString()).as("300 - 100").isEqualTo("200.00");
        assertThat(d.get("netBalance").asString()).as("1000 opening + 500 payments + 80 - 120").isEqualTo("1460.00");
        assertThat(d.get("complaints").get("open").asLong()).isEqualTo(3);
        assertThat(d.get("complaints").get("slaBreached").asLong()).isEqualTo(1);
        assertThat(d.get("unreadSupportMessages").asLong()).isEqualTo(2);
    }

    @Test
    void theNetBalanceFollowsTheLedgerEvenWhenNegative() {
        jdbc.update("update communities set opening_balance = 10.00 where id = ?", communityA.getId());
        asA("POST", LEDGER + "/entries", Map.of("type", "EXPENSE", "categoryId", category("Maintenance", "EXPENSE").toString(), "amount", "75.50", "entryDate", TODAY.toString(), "title", "Big bill"));

        assertThat(dashboard(sessionA).get("netBalance").asString()).isEqualTo("-65.50");
    }

    @Test
    void anEmptyCommunityShowsZerosNotErrors() {
        JsonNode d = dashboard(sessionA);

        assertThat(d.get("members").get("total").asLong()).isZero();
        assertThat(d.get("collection").get("billed").asString()).isEqualTo("0.00");
        assertThat(d.get("overdue").get("amount").asString()).isEqualTo("0.00");
        assertThat(d.get("netBalance").asString()).isEqualTo("0.00");
        assertThat(d.get("upcomingDues").get("items")).isEmpty();
        assertThat(d.get("recentActivity")).isEmpty();
        assertThat(d.get("collectionTrend")).hasSize(12);
        d.get("collectionTrend").forEach(p -> assertThat(p.get("billed").asString()).isEqualTo("0.00"));
    }

    // ---- upcoming dues -----------------------------------------------------------------------------------------------------

    @Test
    void upcomingDuesAreTheUnpaidOnesFallingDueSoonSoonestFirst() {
        seedCommunityA();

        JsonNode dues = dashboard(sessionA).get("upcomingDues");

        assertThat(dues.get("days").asInt()).isEqualTo(14);
        assertThat(dues.get("count").asLong()).as("due in 5 and 10 days; not the one due in 20, not the overdue one").isEqualTo(2);
        assertThat(dues.get("amount").asString()).isEqualTo("750.00");
        assertThat(dues.get("items")).hasSize(2);
        assertThat(dues.get("items").get(0).get("memberName").asString()).isEqualTo("Bina");
        assertThat(dues.get("items").get(0).get("balance").asString()).isEqualTo("500.00");
        assertThat(dues.get("items").get(0).get("dueDate").asString()).isEqualTo(TODAY.plusDays(5).toString());
        assertThat(dues.get("items").get(1).get("memberName").asString()).isEqualTo("Dev");
        assertThat(dues.get("items").get(0).get("invoiceNo").asString()).startsWith("INV-");
    }

    @Test
    void aPartlyPaidInvoiceCountsForWhatIsLeft() {
        UUID m = payer("Partial");
        UUID inv = invoiceWithDue(m, "1000.00", 4);
        payOk(sessionA, inv, "300.00");

        JsonNode dues = dashboard(sessionA).get("upcomingDues");

        assertThat(dues.get("amount").asString()).isEqualTo("700.00");
        assertThat(dues.get("items").get(0).get("status").asString()).isEqualTo("PARTIAL");
    }

    @Test
    void onlyTheSoonestTenAreListedButTheTotalCoversAll() {
        UUID m = payer("Many");
        for (int i = 0; i < 12; i++) invoiceWithDue(m, "10.00", 1 + (i % 12));

        JsonNode dues = dashboard(sessionA).get("upcomingDues");

        assertThat(dues.get("count").asLong()).isEqualTo(12);
        assertThat(dues.get("amount").asString()).isEqualTo("120.00");
        assertThat(dues.get("items")).hasSize(10);
    }

    // ---- trend -----------------------------------------------------------------------------------------------------------

    @Test
    void theTrendCoversTwelveMonthsOldestFirstWithZerosBetween() {
        seedCommunityA();
        UUID m = payer("Old");
        UUID old = invoiceWithDue(m, "700.00", 5);
        LocalDate twoMonthsAgo = TODAY.minusMonths(2).withDayOfMonth(5);
        jdbc.update("update invoices set issued_on = ? where id = ?", twoMonthsAgo, old);
        payOk(sessionA, old, "300.00");
        jdbc.update("update payment_records set received_on = ? where invoice_id = ? and amount > 0", twoMonthsAgo.plusDays(2), old);
        UUID older = invoiceWithDue(m, "90.00", 5);
        jdbc.update("update invoices set issued_on = ? where id = ?", TODAY.minusMonths(11).withDayOfMonth(1), older);
        UUID tooOld = invoiceWithDue(m, "5555.00", 5);
        jdbc.update("update invoices set issued_on = ? where id = ?", TODAY.minusMonths(12).withDayOfMonth(1), tooOld);
        dashboards.evictAll();

        JsonNode trend = dashboard(sessionA).get("collectionTrend");

        assertThat(trend).hasSize(12);
        List<String> months = new ArrayList<>();
        trend.forEach(p -> months.add(p.get("month").asString()));
        assertThat(months.get(0)).isEqualTo(yearMonth(TODAY.minusMonths(11)));
        assertThat(months.get(11)).isEqualTo(yearMonth(TODAY));
        assertThat(months).isSorted().doesNotHaveDuplicates();
        assertThat(trend.get(11).get("billed").asString()).as("this month: the seeded invoices, plus the one made just now").isEqualTo("2050.00");
        assertThat(trend.get(9).get("billed").asString()).isEqualTo("700.00");
        assertThat(trend.get(9).get("collected").asString()).isEqualTo("300.00");
        assertThat(trend.get(0).get("billed").asString()).isEqualTo("90.00");
        assertThat(trend.get(5).get("billed").asString()).isEqualTo("0.00");
        trend.forEach(p -> assertThat(p.get("billed").asString()).isNotEqualTo("5555.00"));
    }

    // ---- recent activity -----------------------------------------------------------------------------------------------------

    @Test
    void recentActivityIsTheLastTenThingsInPlainLanguage() {
        UUID m = payer("Active");
        UUID inv = invoiceWithDue(m, "500.00", 5);
        payOk(sessionA, inv, "200.00");
        for (int i = 0; i < 12; i++) asA("POST", "/api/v1/community/complaints", Map.of("subject", "Issue " + i, "description", "x"));

        JsonNode activity = dashboard(sessionA).get("recentActivity");

        assertThat(activity).hasSize(10);
        assertThat(activity.get(0).get("summary").asString()).isEqualTo("Logged a complaint");
        assertThat(activity.get(0).get("actor").asString()).isNotBlank();
        List<String> times = new ArrayList<>();
        activity.forEach(a -> times.add(a.get("at").asString()));
        assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void paymentsAreDescribedWithTheirAmountAndInvoice() {
        UUID m = payer("Payer");
        UUID inv = invoiceWithDue(m, "500.00", 5);
        payOk(sessionA, inv, "200.00");
        String invoiceNo = invoiceView(sessionA, inv).get("invoice").get("invoiceNo").asString();

        List<String> summaries = new ArrayList<>();
        dashboard(sessionA).get("recentActivity").forEach(a -> summaries.add(a.get("summary").asString()));

        assertThat(summaries).anyMatch(s -> s.startsWith("Recorded a payment of ₹200.00 on invoice " + invoiceNo));
        assertThat(summaries).anyMatch(s -> s.equals("Created invoice " + invoiceNo + " of ₹500.00"));
    }

    @Test
    void noiseIsLeftOutAndOtherCommunitiesNeverAppear() {
        asA("GET", "/api/v1/community/support/summary", null);
        UUID threadOfB = id(asB("POST", "/api/v1/community/support/threads", Map.of("subject", "B help", "body", "x")).json());
        asB("POST", "/api/v1/community/complaints", Map.of("subject", "B only", "description", "x"));
        UUID thread = id(asA("POST", "/api/v1/community/support/threads", Map.of("subject", "A help", "body", "x")).json());
        asSuper("POST", "/api/v1/admin/support/threads/" + thread + "/messages", Map.of("body", "reply"));
        asA("POST", "/api/v1/community/support/threads/" + thread + "/read", null);
        asA("POST", LEDGER + "/attachments/upload-url", Map.of("contentType", "image/png", "sizeBytes", 100));

        JsonNode activity = dashboard(sessionA).get("recentActivity");

        List<String> actions = new ArrayList<>();
        activity.forEach(a -> actions.add(a.get("action").asString()));
        assertThat(actions).doesNotContain("SUPPORT_THREAD_READ", "LEDGER_ATTACHMENT_UPLOAD_REQUESTED", "LOGIN_SUCCESS");
        assertThat(actions).contains("SUPPORT_THREAD_CREATED", "SUPPORT_MESSAGE_POSTED");
        assertThat(activity.toString()).doesNotContain("B only");
        assertThat(threadOfB).isNotNull();
        assertThat(count("select count(*) from audit_logs where community_id = ?", communityB.getId())).isPositive();
    }

    @Test
    void whatStaffDidIsLabelledAsPlatformStaff() {
        UUID thread = id(asA("POST", "/api/v1/community/support/threads", Map.of("subject", "A help", "body", "x")).json());
        asSuper("PATCH", "/api/v1/admin/support/threads/" + thread, Map.of("status", "RESOLVED"));

        JsonNode first = dashboard(sessionA).get("recentActivity").get(0);

        assertThat(first.get("action").asString()).isEqualTo("SUPPORT_THREAD_UPDATED");
        assertThat(first.get("actor").asString()).isEqualTo("Amanah Connect staff");
        assertThat(first.get("byPlatformStaff").asBoolean()).isTrue();
    }

    // ---- isolation, cache, cost ------------------------------------------------------------------------------------------------

    @Test
    void eachCommunitySeesOnlyItsOwnFigures() {
        seedCommunityA();
        UUID b = member(sessionB, "Only B", email(), true);
        invoice(sessionB, b, "77.00", TODAY.plusDays(2));

        JsonNode a = dashboard(sessionA);
        JsonNode other = dashboard(sessionB);

        assertThat(a.get("members").get("total").asLong()).isEqualTo(5);
        assertThat(other.get("members").get("total").asLong()).isEqualTo(1);
        assertThat(other.get("collection").get("billed").asString()).isEqualTo("77.00");
        assertThat(other.get("complaints").get("open").asLong()).isZero();
        assertThat(other.get("unreadSupportMessages").asLong()).isZero();
        assertThat(other.toString()).doesNotContain("Asha", "Bina", "Gas smell");
        assertTenantSingleton("GET", D, null, () -> asB("GET", D, null).body().replaceAll("\"generatedAt\":\"[^\"]*\"", ""));
    }

    @Test
    void onlyCommunityAdminsMayLookAndOnlyAtTheirOwn() {
        assertThat(asSuper("GET", D, null).status()).isIn(401, 403);
        assertThat(api.get(D).status()).isEqualTo(401);
        ApiClient.Response forged = call(sessionA, "GET", D + "?communityId=" + communityB.getId(), null, "X-Community-Id", communityB.getId().toString());
        assertThat(forged.status()).isEqualTo(200);
        assertThat(forged.json().get("members").get("total").asLong()).as("the community in the request is ignored").isEqualTo(count("select count(*) from members where community_id = ? and deleted_at is null", communityA.getId()));
    }

    @Test
    void theAnswerIsReusedFor30SecondsAndThenWorkedOutAgain() {
        payer("First");
        JsonNode first = dashboard(sessionA);
        jdbc.update("insert into members (community_id, member_no, full_name, status) values (?, 'ZZ-9', 'Late arrival', 'ACTIVE')", communityA.getId());

        JsonNode again = dashboard(sessionA);
        assertThat(again.get("members").get("total").asLong()).as("served from the cache").isEqualTo(first.get("members").get("total").asLong());
        assertThat(again.get("generatedAt").asString()).isEqualTo(first.get("generatedAt").asString());

        dashboards.evict(communityA.getId());
        assertThat(dashboard(sessionA).get("members").get("total").asLong()).isEqualTo(first.get("members").get("total").asLong() + 1);
    }

    @Test
    void theCacheIsPerCommunity() {
        payer("In A");
        member(sessionB, "In B", email(), true);
        member(sessionB, "In B too", email(), true);

        assertThat(dashboard(sessionA).get("members").get("total").asLong()).isEqualTo(1);
        assertThat(dashboard(sessionB).get("members").get("total").asLong()).isEqualTo(2);
        assertThat(dashboard(sessionA).get("members").get("total").asLong()).isEqualTo(1);
    }

    @Test
    void theNumberOfQueriesDoesNotGrowWithTheData() {
        UUID m = payer("Few");
        invoiceWithDue(m, "10.00", 3);
        dashboards.evictAll();
        long before = CountingJdbcTemplate.STATEMENTS.get();
        dashboard(sessionA);
        long small = CountingJdbcTemplate.STATEMENTS.get() - before;

        for (int i = 0; i < 80; i++) {
            UUID extra = payer("Bulk " + i);
            jdbc.update("insert into invoices (community_id, member_id, invoice_no, status, kind, description, amount, amount_paid, issued_on, due_date) values (?, ?, ?, 'ISSUED', 'MAINTENANCE', 'Bulk', 20.00, 0, ?, ?)",
                    communityA.getId(), extra, "BULK-" + i, TODAY, TODAY.plusDays(1 + i % 10));
        }
        for (int i = 0; i < 30; i++) asA("POST", "/api/v1/community/complaints", Map.of("subject", "Bulk " + i, "description", "x"));
        dashboards.evictAll();
        long mid = CountingJdbcTemplate.STATEMENTS.get();
        JsonNode big = dashboard(sessionA);
        long large = CountingJdbcTemplate.STATEMENTS.get() - mid;

        assertThat(big.get("members").get("total").asLong()).isGreaterThan(80);
        assertThat(big.get("upcomingDues").get("count").asLong()).isGreaterThan(80);
        assertThat(large).as("same queries with 80 more invoices and members: no query per row").isEqualTo(small);
        assertThat(large).isLessThanOrEqualTo(8);
    }

    @Test
    void aCachedAnswerCostsNoQueriesAtAll() {
        dashboard(sessionA);
        long before = CountingJdbcTemplate.STATEMENTS.get();

        dashboard(sessionA);

        assertThat(CountingJdbcTemplate.STATEMENTS.get() - before).isLessThanOrEqualTo(2);
    }
}

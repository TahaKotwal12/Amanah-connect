package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class LedgerIT extends AbstractFinanceIT {

    private static final String CATEGORIES = LEDGER + "/categories";
    private static final String ENTRIES = LEDGER + "/entries";

    private UUID category(String name, String type) {
        for (JsonNode c : asA("GET", CATEGORIES + "?includeInactive=true", null).json()) {
            if (c.get("name").asString().equals(name) && c.get("type").asString().equals(type)) return id(c);
        }
        throw new AssertionError("no category " + name);
    }

    private Map<String, Object> entry(String type, UUID category, String amount, LocalDate date, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("categoryId", category.toString());
        body.put("amount", amount);
        body.put("entryDate", date.toString());
        body.put("title", title);
        return body;
    }

    private JsonNode expense(String amount, LocalDate date, String title) {
        ApiClient.Response response = asA("POST", ENTRIES, entry("EXPENSE", category("Maintenance", "EXPENSE"), amount, date, title));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json();
    }

    private JsonNode income(String amount, LocalDate date, String title) {
        ApiClient.Response response = asA("POST", ENTRIES, entry("INCOME", category("Other", "INCOME"), amount, date, title));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json();
    }

    // ---- categories ------------------------------------------------------------------------------------------------------

    @Test
    void aNewCommunityHasTheGenericDefaultCategories() {
        JsonNode categories = asA("GET", CATEGORIES, null).json();

        List<String> income = new ArrayList<>();
        List<String> expense = new ArrayList<>();
        categories.forEach(c -> (c.get("type").asString().equals("INCOME") ? income : expense).add(c.get("name").asString()));
        assertThat(income).containsExactlyInAnyOrder("Membership Fees", "Donations", "Events", "Other");
        assertThat(expense).containsExactlyInAnyOrder("Maintenance", "Utilities", "Repairs", "Events", "Administration", "Other");
        for (JsonNode c : categories) {
            assertThat(c.get("active").asBoolean()).isTrue();
            assertThat(c.get("system").asBoolean()).isEqualTo(c.get("type").asString().equals("INCOME"));
        }
    }

    @Test
    void addsRenamesAndHidesCategories() {
        ApiClient.Response created = asA("POST", CATEGORIES, Map.of("name", "  Security guard ", "type", "EXPENSE"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.json().get("name").asString()).isEqualTo("Security guard");
        assertThat(created.json().get("system").asBoolean()).isFalse();
        UUID id = id(created.json());

        assertThat(asA("POST", CATEGORIES, Map.of("name", "security GUARD", "type", "EXPENSE")).status()).as("same name and type, any case? exact only").isIn(201, 409);
        ApiClient.Response duplicate = asA("POST", CATEGORIES, Map.of("name", "Security guard", "type", "EXPENSE"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("CATEGORY_EXISTS");
        assertThat(asA("POST", CATEGORIES, Map.of("name", "Security guard", "type", "INCOME")).status()).as("the other direction is a different category").isEqualTo(201);
        assertThat(asA("POST", CATEGORIES, Map.of("name", " ", "type", "EXPENSE")).status()).isEqualTo(400);
        assertThat(asA("POST", CATEGORIES, Map.of("name", "x".repeat(101), "type", "EXPENSE")).status()).isEqualTo(400);
        assertThat(asA("POST", CATEGORIES, Map.of("name", "No type")).status()).isEqualTo(400);

        assertThat(asA("PATCH", CATEGORIES + "/" + id, Map.of("name", "Security services")).json().get("name").asString()).isEqualTo("Security services");
        assertThat(asA("PATCH", CATEGORIES + "/" + id, Map.of("name", "Utilities")).code()).isEqualTo("CATEGORY_EXISTS");
        ApiClient.Response hidden = asA("PATCH", CATEGORIES + "/" + id, Map.of("active", false));
        assertThat(hidden.json().get("active").asBoolean()).isFalse();
        assertThat(asA("GET", CATEGORIES, null).body()).doesNotContain(id.toString());
        assertThat(asA("GET", CATEGORIES + "?includeInactive=true", null).body()).contains(id.toString());
        assertThat(asA("POST", ENTRIES, entry("EXPENSE", id, "10.00", TODAY, "Into a hidden category")).status()).isEqualTo(400);
        assertThat(count("select count(*) from audit_logs where entity_id = ? and action like 'LEDGER_CATEGORY_%'", id)).isEqualTo(3);
    }

    @Test
    void theCategoriesPaymentsPostToCanBeRenamedButNotHiddenAndPaymentsKeepFlowing() {
        UUID fees = category("Membership Fees", "INCOME");
        ApiClient.Response hide = asA("PATCH", CATEGORIES + "/" + fees, Map.of("active", false));
        assertThat(hide.status()).isEqualTo(409);
        assertThat(hide.json().toString()).contains("cannot be hidden");

        assertThat(asA("PATCH", CATEGORIES + "/" + fees, Map.of("name", "Society dues")).status()).isEqualTo(200);
        payOk(sessionA, id(invoiceA(memberA("Asha"), "100.00", TODAY.plusDays(5))), "100.00");

        JsonNode entries = asA("GET", ENTRIES + "?source=PAYMENT", null).json();
        assertThat(entries.get("total").asInt()).isOne();
        assertThat(entries.get("items").get(0).get("category").get("name").asString()).as("found by key, not by name").isEqualTo("Society dues");
    }

    // ---- manual entries --------------------------------------------------------------------------------------------------

    @Test
    void addsAnExpenseAndAnIncomeEntry() {
        JsonNode out = expense("1250.50", TODAY.minusDays(2), "Lift repair");
        JsonNode in = income("300.00", TODAY, "Scrap sale");

        assertThat(out.get("type").asString()).isEqualTo("EXPENSE");
        assertThat(out.get("amount").asString()).isEqualTo("1250.50");
        assertThat(out.get("category").get("name").asString()).isEqualTo("Maintenance");
        assertThat(out.get("source").asString()).isEqualTo("MANUAL");
        assertThat(out.get("readOnly").asBoolean()).isFalse();
        assertThat(out.get("reversed").asBoolean()).isFalse();
        assertThat(out.get("entryDate").asString()).isEqualTo(TODAY.minusDays(2).toString());
        assertThat(in.get("type").asString()).isEqualTo("INCOME");
        assertThat(jdbc.queryForObject("select created_by from ledger_entries where id = ?", UUID.class, id(out))).isEqualTo(adminA.id());
        assertThat(count("select count(*) from audit_logs where action = 'LEDGER_ENTRY_CREATED' and community_id = ?", communityA.getId())).isEqualTo(2);
        assertThat(asA("GET", ENTRIES + "/" + id(out), null).json().get("title").asString()).isEqualTo("Lift repair");
    }

    @Test
    void validatesAnEntry() {
        UUID expenseCategory = category("Maintenance", "EXPENSE");
        UUID incomeCategory = category("Donations", "INCOME");
        assertThat(asA("POST", ENTRIES, entry("INCOME", expenseCategory, "10.00", TODAY, "Wrong direction")).status()).as("an expense category cannot take income").isEqualTo(400);
        assertThat(asA("POST", ENTRIES, entry("EXPENSE", incomeCategory, "10.00", TODAY, "Wrong direction")).status()).isEqualTo(400);
        UUID foreign = UUID.fromString(jdbc.queryForObject("select id::text from ledger_categories where community_id = ? limit 1", String.class, communityB.getId()));
        ApiClient.Response foreignResponse = asA("POST", ENTRIES, entry("INCOME", foreign, "10.00", TODAY, "Foreign"));
        ApiClient.Response unknownResponse = asA("POST", ENTRIES, entry("INCOME", UUID.randomUUID(), "10.00", TODAY, "Unknown"));
        assertThat(foreignResponse.status()).isEqualTo(400);
        assertThat(foreignResponse.json().toString()).as("another community's category is just unknown").contains("unknown category");
        assertThat(unknownResponse.json().get("errors").toString()).isEqualTo(foreignResponse.json().get("errors").toString());
        for (String amount : new String[] {"0", "-5", "1.999", "x"}) {
            assertThat(asA("POST", ENTRIES, entry("EXPENSE", expenseCategory, amount, TODAY, "Bad amount")).status()).as(amount).isEqualTo(400);
        }
        assertThat(asA("POST", ENTRIES, entry("EXPENSE", expenseCategory, "10.00", TODAY.plusDays(1), "Future")).status()).isEqualTo(400);
        assertThat(asA("POST", ENTRIES, entry("EXPENSE", expenseCategory, "10.00", TODAY.minusYears(26), "Ancient")).status()).isEqualTo(400);
        assertThat(asA("POST", ENTRIES, entry("EXPENSE", expenseCategory, "10.00", TODAY, " ")).status()).isEqualTo(400);
        Map<String, Object> longNotes = entry("EXPENSE", expenseCategory, "10.00", TODAY, "Notes");
        longNotes.put("notes", "n".repeat(2001));
        assertThat(asA("POST", ENTRIES, longNotes).status()).isEqualTo(400);
        assertThat(count("select count(*) from ledger_entries where community_id = ?", communityA.getId())).isZero();
    }

    @Test
    void editsTheDescriptiveFieldsButNeverTheAmount() {
        JsonNode created = expense("500.00", TODAY.minusDays(5), "Pump repair");
        UUID utilities = category("Utilities", "EXPENSE");

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("title", "Pump motor repair");
        patch.put("notes", "Invoice 1234");
        patch.put("entryDate", TODAY.minusDays(4).toString());
        patch.put("categoryId", utilities.toString());
        patch.put("amount", "9999.00");
        patch.put("type", "INCOME");
        ApiClient.Response edited = asA("PATCH", ENTRIES + "/" + id(created), patch);

        assertThat(edited.status()).as(edited.body()).isEqualTo(200);
        assertThat(edited.json().get("title").asString()).isEqualTo("Pump motor repair");
        assertThat(edited.json().get("notes").asString()).isEqualTo("Invoice 1234");
        assertThat(edited.json().get("category").get("name").asString()).isEqualTo("Utilities");
        assertThat(edited.json().get("amount").asString()).as("the amount is not editable").isEqualTo("500.00");
        assertThat(edited.json().get("type").asString()).isEqualTo("EXPENSE");
        Map<String, Object> audit = jdbc.queryForMap("select before::text as b, after::text as a from audit_logs where action = 'LEDGER_ENTRY_UPDATED' and entity_id = ?", id(created));
        assertThat(audit.get("b").toString()).contains("Pump repair").contains("Maintenance");
        assertThat(audit.get("a").toString()).contains("Pump motor repair").contains("Utilities");
        assertThat(asA("PATCH", ENTRIES + "/" + id(created), Map.of("categoryId", category("Donations", "INCOME").toString())).status()).as("category must keep the entry's type").isEqualTo(400);
        assertThat(asA("PATCH", ENTRIES + "/" + id(created), Map.of("title", "")).status()).isEqualTo(400);
        assertThat(asA("PATCH", ENTRIES + "/" + UUID.randomUUID(), Map.of("title", "x")).status()).isEqualTo(404);
    }

    @Test
    void entriesFromPaymentsAreReadOnly() {
        payOk(sessionA, id(invoiceA(memberA("Asha"), "100.00", TODAY.plusDays(5))), "100.00");
        JsonNode entry = asA("GET", ENTRIES + "?source=PAYMENT", null).json().get("items").get(0);

        assertThat(entry.get("readOnly").asBoolean()).isTrue();
        ApiClient.Response edit = asA("PATCH", ENTRIES + "/" + id(entry), Map.of("title", "Changed"));
        assertThat(edit.status()).isEqualTo(409);
        assertThat(edit.code()).isEqualTo("LEDGER_ENTRY_READ_ONLY");
        ApiClient.Response reverse = asA("POST", ENTRIES + "/" + id(entry) + "/reverse", Map.of("reason", "x"));
        assertThat(reverse.status()).isEqualTo(409);
        assertThat(reverse.code()).isEqualTo("LEDGER_ENTRY_READ_ONLY");
        assertThat(reverse.json().get("detail").asString()).contains("Reverse the payment");
        assertThat(count("select count(*) from ledger_entries where community_id = ?", communityA.getId())).isOne();
    }

    @Test
    void reversingAManualEntryAddsANegativeTwinThatNetsToZero() {
        JsonNode created = expense("800.00", TODAY.minusDays(3), "Paid twice");
        UUID id = id(created);

        assertThat(asA("POST", ENTRIES + "/" + id + "/reverse", Map.of()).status()).isEqualTo(400);
        assertThat(asA("POST", ENTRIES + "/" + id + "/reverse", Map.of("reason", "x", "entryDate", TODAY.minusDays(4).toString())).status()).as("before the entry").isEqualTo(400);
        ApiClient.Response reversed = asA("POST", ENTRIES + "/" + id + "/reverse", Map.of("reason", "Entered by mistake"));

        assertThat(reversed.status()).as(reversed.body()).isEqualTo(201);
        JsonNode twin = reversed.json();
        assertThat(twin.get("amount").asString()).isEqualTo("-800.00");
        assertThat(twin.get("type").asString()).isEqualTo("EXPENSE");
        assertThat(twin.get("reversedOf").asString()).isEqualTo(id.toString());
        assertThat(twin.get("reversalReason").asString()).isEqualTo("Entered by mistake");
        assertThat(twin.get("readOnly").asBoolean()).isTrue();
        assertThat(asA("GET", ENTRIES + "/" + id, null).json().get("reversed").asBoolean()).isTrue();
        assertThat(asA("GET", LEDGER + "/summary", null).json().get("totalExpense").asString()).isEqualTo("0.00");
        assertThat(count("select count(*) from ledger_entries where community_id = ?", communityA.getId())).as("nothing deleted").isEqualTo(2);

        assertThat(asA("POST", ENTRIES + "/" + id + "/reverse", Map.of("reason", "again")).code()).isEqualTo("ALREADY_REVERSED");
        assertThat(asA("POST", ENTRIES + "/" + id(twin) + "/reverse", Map.of("reason", "undo")).code()).isEqualTo("ALREADY_REVERSED");
        assertThat(asA("PATCH", ENTRIES + "/" + id, Map.of("title", "Edited")).code()).as("a reversed entry is frozen").isEqualTo("LEDGER_ENTRY_READ_ONLY");
        assertThat(count("select count(*) from audit_logs where action = 'LEDGER_ENTRY_REVERSED' and community_id = ?", communityA.getId())).isOne();
    }

    @Test
    void aLedgerEntryIsNeverDeletedAtTheDatabaseEither() {
        UUID id = id(expense("10.00", TODAY, "Keep me"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where id = ?", id)).hasMessageContaining("never deleted");
    }

    // ---- attachments -------------------------------------------------------------------------------------------------------

    private JsonNode uploadUrl(String type, long size) {
        ApiClient.Response response = asA("POST", LEDGER + "/attachments/upload-url", Map.of("contentType", type, "sizeBytes", size));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    @Test
    void attachesAReceiptThroughASignedUpload() {
        JsonNode upload = uploadUrl("application/pdf", 120_000);
        String key = upload.get("attachmentKey").asString();
        assertThat(key).startsWith("communities/" + communityA.getId() + "/ledger/").endsWith(".pdf");
        assertThat(upload.get("method").asString()).isEqualTo("PUT");
        assertThat(upload.get("headers").get("Content-Length").asString()).isEqualTo("120000");
        assertThat(storage.signedSizes.get(key)).isEqualTo(120_000L);
        Map<String, Object> body = entry("EXPENSE", category("Repairs", "EXPENSE"), "450.00", TODAY, "Plumber");

        body.put("attachmentKey", key);
        assertThat(asA("POST", ENTRIES, body).status()).as("not uploaded yet").isEqualTo(400);
        storage.put(key, "application/pdf", 120_000);
        ApiClient.Response created = asA("POST", ENTRIES, body);

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.json().get("attachmentKey").asString()).isEqualTo(key);
        assertThat(created.json().get("attachmentUrl").asString()).contains("download").contains(key);

        JsonNode second = uploadUrl("image/png", 5_000);
        storage.put(second.get("attachmentKey").asString(), "image/png", 5_000);
        ApiClient.Response replaced = asA("PATCH", ENTRIES + "/" + id(created.json()), Map.of("attachmentKey", second.get("attachmentKey").asString()));
        assertThat(replaced.status()).isEqualTo(200);
        assertThat(storage.has(key)).as("the replaced file is deleted").isFalse();
        ApiClient.Response removed = asA("PATCH", ENTRIES + "/" + id(created.json()), Map.of("attachmentKey", ""));
        assertThat(removed.json().get("attachmentKey").isNull()).isTrue();
        assertThat(storage.has(second.get("attachmentKey").asString())).isFalse();
    }

    @Test
    void refusesBadAttachmentsAndOtherCommunitiesFiles() {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("contentType", "image/svg+xml", "sizeBytes", 100), Map.of("contentType", "application/x-msdownload", "sizeBytes", 100), Map.of("contentType", "text/html", "sizeBytes", 100),
                Map.of("contentType", "image/png", "sizeBytes", 5_242_881), Map.of("contentType", "image/png", "sizeBytes", 0), Map.of("contentType", "image/png", "sizeBytes", -1))) {
            assertThat(asA("POST", LEDGER + "/attachments/upload-url", bad).status()).as(bad.toString()).isEqualTo(400);
        }
        assertThat(asA("POST", LEDGER + "/attachments/upload-url", Map.of("contentType", "image/png")).status()).isEqualTo(400);

        Map<String, Object> body = entry("EXPENSE", category("Repairs", "EXPENSE"), "10.00", TODAY, "Attached");
        String foreign = "communities/" + communityB.getId() + "/ledger/" + UUID.randomUUID() + ".pdf";
        storage.put(foreign, "application/pdf", 100);
        body.put("attachmentKey", foreign);
        assertThat(asA("POST", ENTRIES, body).status()).as("another community's file").isEqualTo(400);
        body.put("attachmentKey", "communities/" + communityA.getId() + "/ledger/../../x.pdf");
        assertThat(asA("POST", ENTRIES, body).status()).isEqualTo(400);

        String key = uploadUrl("image/png", 100).get("attachmentKey").asString();
        storage.put(key, "text/html", 100);
        body.put("attachmentKey", key);
        assertThat(asA("POST", ENTRIES, body).status()).as("what is really in storage is checked").isEqualTo(400);
        assertThat(storage.has(key)).isFalse();
        assertThat(count("select count(*) from ledger_entries where community_id = ?", communityA.getId())).isZero();
    }

    // ---- listing and summary --------------------------------------------------------------------------------------------------

    @Test
    void listsAndFiltersEntries() {
        JsonNode e1 = expense("100.00", TODAY.minusDays(40), "Old lift repair");
        JsonNode e2 = expense("200.00", TODAY.minusDays(10), "Garden tools");
        JsonNode i1 = income("300.00", TODAY.minusDays(5), "Hall rent");

        assertThat(ids(asA("GET", ENTRIES + "?type=INCOME", null))).containsExactly(id(i1).toString());
        assertThat(ids(asA("GET", ENTRIES + "?from=" + TODAY.minusDays(20) + "&to=" + TODAY.minusDays(7), null))).containsExactly(id(e2).toString());
        assertThat(ids(asA("GET", ENTRIES + "?q=LIFT", null))).containsExactly(id(e1).toString());
        assertThat(ids(asA("GET", ENTRIES + "?categoryId=" + category("Maintenance", "EXPENSE"), null))).containsExactlyInAnyOrder(id(e1).toString(), id(e2).toString());
        assertThat(ids(asA("GET", ENTRIES + "?source=MANUAL", null))).hasSize(3);
        assertThat(ids(asA("GET", ENTRIES + "?source=PAYMENT", null))).isEmpty();
        assertThat(ids(asA("GET", ENTRIES + "?sort=amount,desc", null))).containsExactly(id(i1).toString(), id(e2).toString(), id(e1).toString());
        assertThat(ids(asA("GET", ENTRIES, null))).as("newest date first").containsExactly(id(i1).toString(), id(e2).toString(), id(e1).toString());
        assertThat(asA("GET", ENTRIES + "?q=%25", null).json().get("total").asInt()).isZero();
        assertThat(asA("GET", ENTRIES + "?type=BOGUS", null).status()).isEqualTo(400);
        assertThat(asA("GET", ENTRIES + "?sort=notes", null).status()).isEqualTo(400);
    }

    private List<String> ids(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        response.json().get("items").forEach(n -> ids.add(n.get("id").asString()));
        return ids;
    }

    @Test
    void theSummaryTotalsByCategoryAndMonthWithTheOpeningBalance() {
        LocalDate month1 = LocalDate.of(2026, 1, 15);
        LocalDate month2 = LocalDate.of(2026, 2, 10);
        asA("POST", ENTRIES, entry("INCOME", category("Donations", "INCOME"), "1000.00", month1, "Donation"));
        asA("POST", ENTRIES, entry("INCOME", category("Other", "INCOME"), "250.50", month2, "Hall rent"));
        asA("POST", ENTRIES, entry("EXPENSE", category("Maintenance", "EXPENSE"), "400.00", month1, "Lift"));
        asA("POST", ENTRIES, entry("EXPENSE", category("Maintenance", "EXPENSE"), "100.25", month2, "Lift again"));
        asA("POST", ENTRIES, entry("EXPENSE", category("Utilities", "EXPENSE"), "300.00", month2, "Electricity"));
        assertThat(asA("PATCH", "/api/v1/community/settings", Map.of("openingBalance", "5000.00")).json().get("openingBalance").asString()).isEqualTo("5000.00");

        JsonNode all = asA("GET", LEDGER + "/summary", null).json();

        assertThat(all.get("totalIncome").asString()).isEqualTo("1250.50");
        assertThat(all.get("totalExpense").asString()).isEqualTo("800.25");
        assertThat(all.get("net").asString()).isEqualTo("450.25");
        assertThat(all.get("openingBalance").asString()).isEqualTo("5000.00");
        assertThat(all.get("closingBalance").asString()).isEqualTo("5450.25");
        Map<String, String> byCategory = new LinkedHashMap<>();
        all.get("byCategory").forEach(c -> byCategory.put(c.get("type").asString() + ":" + c.get("name").asString(), c.get("total").asString()));
        assertThat(byCategory).containsEntry("INCOME:Donations", "1000.00").containsEntry("INCOME:Other", "250.50").containsEntry("EXPENSE:Maintenance", "500.25").containsEntry("EXPENSE:Utilities", "300.00");
        JsonNode months = all.get("byMonth");
        assertThat(months.size()).isEqualTo(2);
        assertThat(months.get(0).get("month").asString()).isEqualTo("2026-01");
        assertThat(months.get(0).get("income").asString()).isEqualTo("1000.00");
        assertThat(months.get(0).get("expense").asString()).isEqualTo("400.00");
        assertThat(months.get(0).get("net").asString()).isEqualTo("600.00");
        assertThat(months.get(1).get("net").asString()).isEqualTo("-149.75");

        JsonNode february = asA("GET", LEDGER + "/summary?from=2026-02-01&to=2026-02-28", null).json();
        assertThat(february.get("totalIncome").asString()).isEqualTo("250.50");
        assertThat(february.get("totalExpense").asString()).isEqualTo("400.25");
        assertThat(february.get("openingBalance").asString()).as("opening balance plus January's net").isEqualTo("5600.00");
        assertThat(february.get("closingBalance").asString()).isEqualTo("5450.25");
        assertThat(asA("GET", LEDGER + "/summary?from=2026-03-01", null).json().get("openingBalance").asString()).isEqualTo("5450.25");
        assertThat(asA("GET", LEDGER + "/summary?to=2026-01-31", null).json().get("closingBalance").asString()).isEqualTo("5600.00");
        assertThat(asA("GET", LEDGER + "/summary?from=2026-03-01&to=2026-02-01", null).status()).isEqualTo(400);
        assertThat(asA("GET", LEDGER + "/summary?from=garbage", null).status()).isEqualTo(400);
    }

    @Test
    void anEmptyLedgerSummarisesToZeroAndTheOpeningBalanceMayBeNegative() {
        JsonNode empty = asA("GET", LEDGER + "/summary", null).json();
        assertThat(empty.get("totalIncome").asString()).isEqualTo("0.00");
        assertThat(empty.get("net").asString()).isEqualTo("0.00");
        assertThat(empty.get("byCategory").size()).isZero();
        assertThat(empty.get("byMonth").size()).isZero();

        assertThat(asA("PATCH", "/api/v1/community/settings", Map.of("openingBalance", "-1200.75")).json().get("openingBalance").asString()).isEqualTo("-1200.75");
        assertThat(asA("GET", LEDGER + "/summary", null).json().get("closingBalance").asString()).isEqualTo("-1200.75");
        assertThat(asA("PATCH", "/api/v1/community/settings", Map.of("openingBalance", "10.999")).status()).isEqualTo(400);
        assertThat(asA("PATCH", "/api/v1/community/settings", Map.of("openingBalance", "abc")).status()).isEqualTo(400);
        Map<String, Object> audit = jdbc.queryForMap("select after::text as a from audit_logs where action = 'COMMUNITY_SETTINGS_UPDATED' and community_id = ? order by created_at desc limit 1", communityA.getId());
        assertThat(audit.get("a").toString()).contains("-1200.75");
        assertThat(money(asB("GET", LEDGER + "/summary", null).json().get("openingBalance"))).as("another community's balance is its own").isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aSuspendedCommunityCanReadButNotWriteItsLedger() {
        UUID id = id(expense("10.00", TODAY, "Existing"));
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());

        assertThat(asA("GET", ENTRIES, null).status()).isEqualTo(200);
        assertThat(asA("GET", LEDGER + "/summary", null).status()).isEqualTo(200);
        assertThat(asA("POST", ENTRIES, entry("EXPENSE", category("Maintenance", "EXPENSE"), "1.00", TODAY, "Late")).code()).isEqualTo("COMMUNITY_SUSPENDED");
        assertThat(asA("POST", ENTRIES + "/" + id + "/reverse", Map.of("reason", "x")).code()).isEqualTo("COMMUNITY_SUSPENDED");
        assertThat(asA("POST", CATEGORIES, Map.of("name", "New", "type", "EXPENSE")).code()).isEqualTo("COMMUNITY_SUSPENDED");
    }
}

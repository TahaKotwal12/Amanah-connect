package com.amanahconnect.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.InMemoryObjectStorage;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class SettingsIT extends AbstractTenantIT {

    static final String SETTINGS = "/api/v1/community/settings";

    @Autowired InMemoryObjectStorage storage;

    private ApiClient.Response patchA(Map<String, Object> body) {
        return asA("PATCH", SETTINGS, body);
    }

    private JsonNode okA(Map<String, Object> body) {
        ApiClient.Response response = patchA(body);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    @Test
    void showsTheDefaults() {
        JsonNode settings = asA("GET", SETTINGS, null).json();

        assertThat(settings.get("currency").asString()).isEqualTo("INR");
        assertThat(settings.get("financialYearStartMonth").asInt()).isEqualTo(4);
        assertThat(settings.get("memberGroupLabel").asString()).isEqualTo("Group");
        assertThat(settings.get("financialSettingsLocked").asBoolean()).isFalse();
        assertThat(settings.get("logoUrl").isNull()).isTrue();
        JsonNode notifications = settings.get("notifications");
        assertThat(notifications.get("dueReminderDaysBefore").asInt()).isEqualTo(3);
        assertThat(notifications.get("overdueReminderEveryDays").asInt()).isEqualTo(7);
        assertThat(notifications.get("sendWelcomeEmail").asBoolean()).isTrue();
        assertThat(notifications.get("sendReceiptEmail").asBoolean()).isTrue();
        assertThat(settings.get("name").asString()).isEqualTo(communityA.getName());
    }

    @Test
    void updatesTheProfilePartiallyAndAuditsBeforeAndAfter() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "  Lotus Residents  ");
        body.put("contactName", "Asha Rao");
        body.put("contactEmail", "office@lotus.example.test");
        body.put("contactPhone", "+91 98765 43210");
        body.put("addressLine1", "12 Garden Road");
        body.put("city", "Pune");
        body.put("state", "Maharashtra");
        body.put("postalCode", "411001");
        body.put("country", "IN");
        body.put("dateOfEstablishment", "2010-04-01");

        JsonNode settings = okA(body);

        assertThat(settings.get("name").asString()).isEqualTo("Lotus Residents");
        assertThat(settings.get("city").asString()).isEqualTo("Pune");
        assertThat(settings.get("dateOfEstablishment").asString()).isEqualTo("2010-04-01");

        JsonNode untouched = okA(Map.of("city", "Mumbai"));
        assertThat(untouched.get("city").asString()).isEqualTo("Mumbai");
        assertThat(untouched.get("contactName").asString()).as("not sent, so unchanged").isEqualTo("Asha Rao");

        JsonNode cleared = okA(Map.of("addressLine1", "", "contactPhone", ""));
        assertThat(cleared.get("addressLine1").isNull()).isTrue();
        assertThat(cleared.get("contactPhone").isNull()).isTrue();

        Map<String, Object> audit = jdbc.queryForMap("select before::text as before, after::text as after from audit_logs where action = 'COMMUNITY_SETTINGS_UPDATED' and community_id = ? order by created_at limit 1", communityA.getId());
        assertThat(audit.get("before").toString()).contains(communityA.getName());
        assertThat(audit.get("after").toString()).contains("Lotus Residents");
    }

    @Test
    void validatesTheProfile() {
        assertThat(patchA(Map.of("name", "")).status()).isEqualTo(400);
        assertThat(patchA(Map.of("contactEmail", "nope")).status()).isEqualTo(400);
        assertThat(patchA(Map.of("contactPhone", "call me")).status()).isEqualTo(400);
        assertThat(patchA(Map.of("country", "india")).status()).isEqualTo(400);
        assertThat(patchA(Map.of("dateOfEstablishment", "2999-01-01")).status()).isEqualTo(400);
        assertThat(patchA(Map.of("name", "x".repeat(201))).status()).isEqualTo(400);
        assertThat(patchA(Map.of("financialYearStartMonth", 13)).status()).isEqualTo(400);
        assertThat(patchA(Map.of("currency", "rupee")).status()).isEqualTo(400);
    }

    // ---- UPI -------------------------------------------------------------------------------------------------

    @Test
    void validatesUpiAndRequiresThePayeeName() {
        for (String bad : new String[] {"nobank", "a@", "@bank", "a b@bank", "a@@bank", "x@1bank", "<script>@bank"}) {
            ApiClient.Response response = patchA(Map.of("upiId", bad, "upiPayeeName", "Lotus Residents"));
            assertThat(response.status()).as(bad).isEqualTo(400);
            assertThat(response.json().toString()).contains("upiId");
        }
        ApiClient.Response noPayee = patchA(Map.of("upiId", "lotus@okhdfcbank"));
        assertThat(noPayee.status()).isEqualTo(400);
        assertThat(noPayee.json().toString()).contains("upiPayeeName");
        assertThat(patchA(Map.of("upiId", "lotus@okhdfcbank", "upiPayeeName", "<b>Lotus</b>")).status()).isEqualTo(400);

        JsonNode ok = okA(Map.of("upiId", "lotus.residents-1@okhdfcbank", "upiPayeeName", "Lotus Residents Welfare Assn."));
        assertThat(ok.get("upiId").asString()).isEqualTo("lotus.residents-1@okhdfcbank");
        assertThat(ok.get("upiPayeeName").asString()).isEqualTo("Lotus Residents Welfare Assn.");
        assertThat(jdbc.queryForObject("select upi_id from communities where id = ?", String.class, communityA.getId())).isEqualTo("lotus.residents-1@okhdfcbank");

        assertThat(patchA(Map.of("upiPayeeName", "")).status()).as("cannot drop the payee while a UPI ID is set").isEqualTo(400);
        JsonNode cleared = okA(Map.of("upiId", "", "upiPayeeName", ""));
        assertThat(cleared.get("upiId").isNull()).isTrue();
    }

    // ---- group label and notifications -------------------------------------------------------------------------

    @Test
    void setsTheMemberGroupLabel() {
        assertThat(okA(Map.of("memberGroupLabel", "Flat")).get("memberGroupLabel").asString()).isEqualTo("Flat");
        assertThat(jdbc.queryForObject("select settings->>'memberGroupLabel' from communities where id = ?", String.class, communityA.getId())).isEqualTo("Flat");
        assertThat(patchA(Map.of("memberGroupLabel", "<b>x</b>")).status()).isEqualTo(400);
        assertThat(patchA(Map.of("memberGroupLabel", "x".repeat(31))).status()).isEqualTo(400);
        assertThat(okA(Map.of("memberGroupLabel", "")).get("memberGroupLabel").asString()).as("cleared: back to the default").isEqualTo("Group");
    }

    @Test
    void updatesNotificationSettingsAndValidatesRanges() {
        JsonNode settings = okA(Map.of("notifications", Map.of("dueReminderDaysBefore", 5, "overdueReminderEveryDays", 14, "sendWelcomeEmail", false, "sendReceiptEmail", false)));

        JsonNode n = settings.get("notifications");
        assertThat(n.get("dueReminderDaysBefore").asInt()).isEqualTo(5);
        assertThat(n.get("overdueReminderEveryDays").asInt()).isEqualTo(14);
        assertThat(n.get("sendWelcomeEmail").asBoolean()).isFalse();
        Map<String, Object> row = jdbc.queryForMap("select * from notification_settings where community_id = ?", communityA.getId());
        assertThat(row.get("due_reminder_days_before")).isEqualTo(5);
        assertThat(row.get("send_receipt")).isEqualTo(false);

        JsonNode partial = okA(Map.of("notifications", Map.of("sendWelcomeEmail", true)));
        assertThat(partial.get("notifications").get("dueReminderDaysBefore").asInt()).as("only what was sent changes").isEqualTo(5);
        assertThat(partial.get("notifications").get("sendWelcomeEmail").asBoolean()).isTrue();

        assertThat(patchA(Map.of("notifications", Map.of("dueReminderDaysBefore", 61))).status()).isEqualTo(400);
        assertThat(patchA(Map.of("notifications", Map.of("dueReminderDaysBefore", -1))).status()).isEqualTo(400);
        assertThat(patchA(Map.of("notifications", Map.of("overdueReminderEveryDays", 0))).status()).isEqualTo(400);
        assertThat(patchA(Map.of("notifications", Map.of("overdueReminderEveryDays", 91))).status()).isEqualTo(400);
        assertThat(okA(Map.of("notifications", Map.of("dueReminderDaysBefore", 0))).get("notifications").get("dueReminderDaysBefore").asInt()).as("0 = on the due date").isZero();
    }

    // ---- currency and financial year ------------------------------------------------------------------------------

    @Test
    void currencyAndFinancialYearChangeUntilFinancialRecordsExist() {
        JsonNode changed = okA(Map.of("currency", "USD", "financialYearStartMonth", 1));
        assertThat(changed.get("currency").asString()).isEqualTo("USD");
        assertThat(changed.get("financialYearStartMonth").asInt()).isEqualTo(1);
        assertThat(patchA(Map.of("currency", "ZZZ")).status()).as("not a currency").isEqualTo(400);

        UUID member = data.member(communityA).getId();
        jdbc.update("insert into invoices (id, community_id, member_id, invoice_no, kind, amount, due_date, status) values (gen_random_uuid(), ?, ?, 'LOCK-1', 'MEMBERSHIP', 10, current_date, 'ISSUED')", communityA.getId(), member);

        assertThat(asA("GET", SETTINGS, null).json().get("financialSettingsLocked").asBoolean()).isTrue();
        ApiClient.Response locked = patchA(Map.of("currency", "INR"));
        assertThat(locked.status()).isEqualTo(409);
        assertThat(locked.code()).isEqualTo("SETTING_LOCKED");
        assertThat(patchA(Map.of("financialYearStartMonth", 4)).status()).isEqualTo(409);
        assertThat(patchA(Map.of("currency", "USD", "financialYearStartMonth", 1, "city", "Same values")).status()).as("restating the current values is not a change").isEqualTo(200);
        assertThat(jdbc.queryForObject("select currency from communities where id = ?", String.class, communityA.getId())).isEqualTo("USD");
    }

    @Test
    void aLedgerEntryAlsoLocksTheFinancialSettings() {
        Object category = jdbc.queryForObject("select id from ledger_categories where community_id = ? and type = 'INCOME' limit 1", Object.class, communityA.getId());
        jdbc.update("insert into ledger_entries (id, community_id, type, category_id, amount, entry_date, title, created_by) values (gen_random_uuid(), ?, 'INCOME', ?, 5, current_date, 'x', ?)", communityA.getId(), category, adminA.id());
        assertThat(patchA(Map.of("currency", "USD")).status()).isEqualTo(409);
    }

    @Test
    void aStaleVersionIsRefused() {
        long version = asA("GET", SETTINGS, null).json().get("version").asLong();
        assertThat(patchA(Map.of("city", "First", "version", version)).status()).isEqualTo(200);

        ApiClient.Response stale = patchA(Map.of("city", "Second", "version", version));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(asA("GET", SETTINGS, null).json().get("city").asString()).isEqualTo("First");
    }

    // ---- logo --------------------------------------------------------------------------------------------------------

    private JsonNode uploadUrl(String type, long size) {
        ApiClient.Response response = asA("POST", SETTINGS + "/logo/upload-url", Map.of("contentType", type, "sizeBytes", size));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    @Test
    void uploadsALogoThroughASignedUrl() {
        JsonNode upload = uploadUrl("image/png", 20_000);
        String key = upload.get("logoKey").asString();

        assertThat(key).startsWith("communities/" + communityA.getId() + "/logo/").endsWith(".png");
        assertThat(upload.get("method").asString()).isEqualTo("PUT");
        assertThat(upload.get("uploadUrl").asString()).contains(key);
        assertThat(upload.get("headers").get("Content-Type").asString()).isEqualTo("image/png");
        assertThat(upload.get("headers").get("Content-Length").asString()).isEqualTo("20000");
        assertThat(storage.signedSizes.get(key)).as("the signed size is the declared size").isEqualTo(20_000L);

        ApiClient.Response early = patchA(Map.of("logoKey", key));
        assertThat(early.status()).as("not uploaded yet").isEqualTo(400);
        assertThat(early.json().toString()).contains("has not been uploaded");

        storage.put(key, "image/png", 20_000);
        JsonNode settings = okA(Map.of("logoKey", key));
        assertThat(settings.get("logoKey").asString()).isEqualTo(key);
        assertThat(settings.get("logoUrl").asString()).contains("download").contains(key);
        assertThat(jdbc.queryForObject("select logo_key from communities where id = ?", String.class, communityA.getId())).isEqualTo(key);
    }

    @Test
    void refusesLogosOfTheWrongTypeSizeOrOwner() {
        assertThat(asA("POST", SETTINGS + "/logo/upload-url", Map.of("contentType", "image/svg+xml", "sizeBytes", 100)).status()).as("SVG can carry scripts").isEqualTo(400);
        assertThat(asA("POST", SETTINGS + "/logo/upload-url", Map.of("contentType", "application/pdf", "sizeBytes", 100)).status()).isEqualTo(400);
        assertThat(asA("POST", SETTINGS + "/logo/upload-url", Map.of("contentType", "image/png", "sizeBytes", 524_289)).status()).isEqualTo(400);
        assertThat(asA("POST", SETTINGS + "/logo/upload-url", Map.of("contentType", "image/png", "sizeBytes", 0)).status()).isEqualTo(400);
        assertThat(asA("POST", SETTINGS + "/logo/upload-url", Map.of("contentType", "image/png")).status()).isEqualTo(400);

        // B's logo key cannot be claimed by A, even when the object exists.
        String foreign = "communities/" + communityB.getId() + "/logo/" + UUID.randomUUID() + ".png";
        storage.put(foreign, "image/png", 100);
        assertThat(patchA(Map.of("logoKey", foreign)).status()).isEqualTo(400);
        String madeUp = "communities/" + communityA.getId() + "/logo/../../" + communityB.getId() + "/logo/x.png";
        assertThat(patchA(Map.of("logoKey", madeUp)).status()).isEqualTo(400);

        // What actually landed in storage is checked, not what the client claimed.
        String key = uploadUrl("image/png", 100).get("logoKey").asString();
        storage.put(key, "text/html", 100);
        assertThat(patchA(Map.of("logoKey", key)).status()).isEqualTo(400);
        assertThat(storage.has(key)).as("an unacceptable upload is thrown away").isFalse();
        String big = uploadUrl("image/webp", 100).get("logoKey").asString();
        storage.put(big, "image/webp", 9_999_999);
        assertThat(patchA(Map.of("logoKey", big)).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select logo_key from communities where id = ?", String.class, communityA.getId())).isNull();
    }

    @Test
    void replacingAndRemovingTheLogoCleansUpTheOldFile() {
        String first = uploadUrl("image/png", 100).get("logoKey").asString();
        storage.put(first, "image/png", 100);
        okA(Map.of("logoKey", first));
        String second = uploadUrl("image/jpeg", 100).get("logoKey").asString();
        storage.put(second, "image/jpeg", 100);

        okA(Map.of("logoKey", second));
        assertThat(storage.has(first)).as("the replaced file is deleted").isFalse();
        assertThat(storage.has(second)).isTrue();

        ApiClient.Response removed = asA("DELETE", SETTINGS + "/logo", null);
        assertThat(removed.status()).isEqualTo(200);
        assertThat(removed.json().get("logoKey").isNull()).isTrue();
        assertThat(removed.json().get("logoUrl").isNull()).isTrue();
        assertThat(storage.has(second)).isFalse();
        assertThat(asA("DELETE", SETTINGS + "/logo", null).status()).as("nothing to remove is fine").isEqualTo(200);
        assertThat(patchA(Map.of("logoKey", "")).status()).isEqualTo(400);
    }

    // ---- tenant isolation -------------------------------------------------------------------------------------------------

    @Test
    void settingsOfOneCommunityAreNeverTouchedByAnother() {
        assertTenantSingleton("GET", SETTINGS, null, () -> asB("GET", SETTINGS, null).body());
        Supplier<String> readB = () -> asB("GET", SETTINGS, null).body();
        assertTenantSingleton("PATCH", SETTINGS, Map.of("city", "Only A", "memberGroupLabel", "Flat", "notifications", Map.of("dueReminderDaysBefore", 9)), readB);
        assertTenantSingleton("POST", SETTINGS + "/logo/upload-url", Map.of("contentType", "image/png", "sizeBytes", 100), readB);
        assertTenantSingleton("DELETE", SETTINGS + "/logo", null, readB);

        JsonNode a = asA("GET", SETTINGS, null).json();
        JsonNode b = asB("GET", SETTINGS, null).json();
        assertThat(a.get("city").asString()).isEqualTo("Only A");
        assertThat(a.get("notifications").get("dueReminderDaysBefore").asInt()).isEqualTo(9);
        assertThat(b.get("city").isNull()).isTrue();
        assertThat(b.get("notifications").get("dueReminderDaysBefore").asInt()).isEqualTo(3);
        assertThat(b.get("name").asString()).isEqualTo(communityB.getName());
    }

    @Test
    void aSuspendedCommunityCanReadButNotChangeItsSettings() {
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());
        assertThat(asA("GET", SETTINGS, null).status()).isEqualTo(200);
        ApiClient.Response write = patchA(Map.of("city", "x"));
        assertThat(write.status()).isEqualTo(403);
        assertThat(write.code()).isEqualTo("COMMUNITY_SUSPENDED");
    }

    private interface Supplier<T> extends java.util.function.Supplier<T> {}
}

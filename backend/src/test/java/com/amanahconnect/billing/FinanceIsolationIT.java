package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Every finance endpoint, A against B's data: 404 (same as missing), nothing changed, and A can never write into B. */
class FinanceIsolationIT extends AbstractFinanceIT {

    private UUID memberB() {
        return member(sessionB, "B Member " + UUID.randomUUID().toString().substring(0, 6), email(), true);
    }

    private UUID invoiceB() {
        return id(invoice(sessionB, memberB(), "500.00", TODAY.plusDays(10)));
    }

    private String invoiceState(UUID id) {
        return jdbc.queryForObject("select status || '/' || amount_paid || '/' || version from invoices where id = ?", String.class, id);
    }

    private Runnable invoiceUnchanged(UUID id) {
        String before = invoiceState(id);
        return () -> assertThat(invoiceState(id)).isEqualTo(before);
    }

    private Map<String, Object> payBody() {
        return Map.of("amount", "100.00", "method", "CASH");
    }

    // ---- fee plans -------------------------------------------------------------------------------------------------------

    @Test
    void feePlans() {
        UUID planB = id(feePlan(sessionB, "B plan", "300.00", "MONTHLY", Map.of()));
        assertListHides(PLANS, planB);
        assertCrossTenantRead(PLANS + "/" + planB);
        String before = jdbc.queryForObject("select name || amount from fee_plans where id = ?", String.class, planB);
        assertCrossTenantUpdate("PATCH", PLANS + "/" + planB, Map.of("name", "Hijacked", "amount", "1.00"),
                () -> assertThat(jdbc.queryForObject("select name || amount from fee_plans where id = ?", String.class, planB)).isEqualTo(before));
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Plan " + UUID.randomUUID(), "kind", "MAINTENANCE", "amount", "10.00", "frequency", "MONTHLY"));
        assertCreateCannotTargetOtherTenant(PLANS, body, r -> id(r.json()), PLANS + "/%s");
    }

    @Test
    void aCannotSelectBsMembersOnAPlan() {
        UUID b = memberB();
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Sneaky", "kind", "MAINTENANCE", "amount", "10.00", "frequency", "MONTHLY", "appliesTo", "SELECTED"));
        body.put("memberIds", java.util.List.of(b.toString()));
        ApiClient.Response response = asA("POST", PLANS, body);
        assertThat(response.status()).as(response.body()).isIn(404, 422, 400);
    }

    // ---- invoices --------------------------------------------------------------------------------------------------------

    @Test
    void invoiceReadsAndList() {
        UUID b = invoiceB();
        assertListHides(INVOICES, b);
        assertCrossTenantRead(INVOICES + "/" + b);
        assertCrossTenantRead(INVOICES + "/" + b + "/qr".replace("/qr", "")); // plain read again, harmless
    }

    @Test
    void invoiceQrNeedsTheOwner() {
        setUpi(sessionB);
        UUID b = invoiceB();
        assertCrossTenantRead(INVOICES + "/" + b + "/qr");
    }

    @Test
    void invoiceUpdateAndIssue() {
        UUID memberB = memberB();
        Map<String, Object> draft = new LinkedHashMap<>(Map.of("memberId", memberB.toString(), "kind", "MAINTENANCE", "description", "Draft", "amount", "50.00", "dueDate", TODAY.plusDays(5).toString(), "draft", true));
        ApiClient.Response created = call(sessionB, "POST", INVOICES, draft);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        UUID b = id(created.json());
        assertCrossTenantUpdate("PATCH", INVOICES + "/" + b, Map.of("amount", "999.00"), invoiceUnchanged(b));
        UUID draftB = id(call(sessionB, "POST", INVOICES, draft).json());
        assertCrossTenantUpdate("POST", INVOICES + "/" + draftB + "/issue", null, invoiceUnchanged(draftB));
    }

    @Test
    void invoiceCancel() {
        UUID b = invoiceB();
        assertCrossTenantUpdate("POST", INVOICES + "/" + b + "/cancel", Map.of("reason", "not yours"), invoiceUnchanged(b));
    }

    @Test
    void invoicePayLinkAndResend() {
        setUpi(sessionB);
        UUID b = invoiceB();
        long links = count("select count(*) from payment_links where community_id = ?", communityB.getId());
        assertCrossTenantUpdate("POST", INVOICES + "/" + b + "/pay-link", null,
                () -> assertThat(count("select count(*) from payment_links where community_id = ?", communityB.getId())).isEqualTo(links));
        UUID r = invoiceB();
        long emails = count("select count(*) from email_outbox where community_id = ?", communityB.getId());
        assertCrossTenantUpdate("POST", INVOICES + "/" + r + "/resend", null,
                () -> assertThat(count("select count(*) from email_outbox where community_id = ?", communityB.getId())).isEqualTo(emails));
    }

    @Test
    void creatingAnInvoiceNeverTargetsBOrUsesBsMember() {
        UUID mine = memberA("Mine");
        Map<String, Object> body = new LinkedHashMap<>(Map.of("memberId", mine.toString(), "kind", "MAINTENANCE", "description", "x", "amount", "10.00", "dueDate", TODAY.plusDays(3).toString()));
        assertCreateCannotTargetOtherTenant(INVOICES, body, r -> id(r.json()), INVOICES + "/%s");

        body.put("memberId", memberB().toString());
        long before = count("select count(*) from invoices where community_id = ?", communityB.getId());
        ApiClient.Response foreign = asA("POST", INVOICES, body);
        assertThat(foreign.status()).as(foreign.body()).isEqualTo(404);
        assertThat(count("select count(*) from invoices where community_id = ?", communityB.getId())).isEqualTo(before);
    }

    @Test
    void generatingWithBsPlanIsNotFound() {
        memberA("Someone");
        UUID planB = id(feePlan(sessionB, "B monthly", "100.00", "MONTHLY", Map.of()));
        ApiClient.Response foreign = asA("POST", INVOICES + "/generate", Map.of("feePlanId", planB.toString()));
        assertThat(foreign.status()).as(foreign.body()).isEqualTo(404);
        assertThat(count("select count(*) from invoices where fee_plan_id = ?", planB)).isZero();

        UUID planA = id(feePlan(sessionA, "A monthly", "100.00", "MONTHLY", Map.of()));
        memberB();
        assertTenantSingleton("POST", INVOICES + "/generate", Map.of("feePlanId", planA.toString()),
                () -> asB("GET", INVOICES, null).body());
    }

    // ---- payments --------------------------------------------------------------------------------------------------------

    @Test
    void recordingAPaymentOnBsInvoice() {
        UUID b = invoiceB();
        assertCrossTenantUpdate("POST", INVOICES + "/" + b + "/payments", payBody(), invoiceUnchanged(b));
        assertThat(count("select count(*) from payment_records where invoice_id = ? and community_id <> ?", b, communityB.getId())).isZero();
    }

    @Test
    void paymentReadsListAndReverse() {
        UUID invoice = invoiceB();
        JsonNode payment = payOk(sessionB, invoice, "100.00");
        UUID p = id(payment.has("payment") ? payment.get("payment") : payment);
        assertListHides(PAYMENTS, p);
        assertCrossTenantRead(PAYMENTS + "/" + p);
        assertCrossTenantUpdate("POST", PAYMENTS + "/" + p + "/reverse", Map.of("reason", "not yours"), invoiceUnchanged(invoice));
    }

    @Test
    void donations() {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("donorName", "Anonymous friend", "amount", "75.00", "method", "CASH"));
        assertCreateCannotTargetOtherTenant(DONATIONS, body, r -> {
            JsonNode j = r.json();
            return id(j.has("payment") ? j.get("payment") : j);
        }, PAYMENTS + "/%s");
        Map<String, Object> usesB = new LinkedHashMap<>(body);
        usesB.remove("donorName");
        usesB.put("memberId", memberB().toString());
        assertThat(asA("POST", DONATIONS, usesB).status()).isEqualTo(404);
    }

    // ---- receipts --------------------------------------------------------------------------------------------------------

    @Test
    void receipts() {
        UUID invoice = invoiceB();
        JsonNode payment = payOk(sessionB, invoice, "100.00");
        JsonNode p = payment.has("payment") ? payment.get("payment") : payment;
        UUID receipt = UUID.fromString(jdbc.queryForObject("select id::text from receipts where community_id = ?", String.class, communityB.getId()));
        assertThat(p).isNotNull();
        assertListHides(RECEIPTS, receipt);
        assertCrossTenantRead(RECEIPTS + "/" + receipt);
        assertCrossTenantRead(RECEIPTS + "/" + receipt + "/pdf");
        long emails = count("select count(*) from email_outbox where community_id = ?", communityB.getId());
        assertCrossTenantUpdate("POST", RECEIPTS + "/" + receipt + "/resend", null,
                () -> assertThat(count("select count(*) from email_outbox where community_id = ?", communityB.getId())).isEqualTo(emails));
    }

    // ---- ledger ----------------------------------------------------------------------------------------------------------

    private UUID categoryOf(Session session, String name, String type) {
        for (JsonNode c : call(session, "GET", LEDGER + "/categories", null).json()) {
            if (c.get("name").asString().equals(name) && c.get("type").asString().equals(type)) return id(c);
        }
        throw new AssertionError(name);
    }

    private UUID entryB() {
        Map<String, Object> body = Map.of("type", "EXPENSE", "categoryId", categoryOf(sessionB, "Maintenance", "EXPENSE").toString(), "amount", "20.00", "entryDate", TODAY.toString(), "title", "B spend");
        ApiClient.Response r = call(sessionB, "POST", LEDGER + "/entries", body);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return id(r.json());
    }

    @Test
    void ledgerCategories() {
        ApiClient.Response created = call(sessionB, "POST", LEDGER + "/categories", Map.of("name", "B only", "type", "EXPENSE"));
        UUID c = id(created.json());
        assertListHides(LEDGER + "/categories?includeInactive=true", c);
        String before = jdbc.queryForObject("select name || active from ledger_categories where id = ?", String.class, c);
        assertCrossTenantUpdate("PATCH", LEDGER + "/categories/" + c, Map.of("name", "Renamed by A"),
                () -> assertThat(jdbc.queryForObject("select name || active from ledger_categories where id = ?", String.class, c)).isEqualTo(before));
        String name = "A cat " + UUID.randomUUID().toString().substring(0, 5);
        Map<String, Object> forged = new LinkedHashMap<>(Map.of("name", name, "type", "EXPENSE", "communityId", communityB.getId().toString()));
        ApiClient.Response made = call(sessionA, "POST", LEDGER + "/categories?communityId=" + communityB.getId(), forged, "X-Community-Id", communityB.getId().toString());
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        assertThat(asA("GET", LEDGER + "/categories", null).body()).contains(name);
        assertThat(asB("GET", LEDGER + "/categories?includeInactive=true", null).body()).doesNotContain(name);
        markCovered("POST", LEDGER + "/categories");
    }

    @Test
    void ledgerEntries() {
        UUID e = entryB();
        assertListHides(LEDGER + "/entries", e);
        assertCrossTenantRead(LEDGER + "/entries/" + e);
        String before = jdbc.queryForObject("select title || amount from ledger_entries where id = ?", String.class, e);
        assertCrossTenantUpdate("PATCH", LEDGER + "/entries/" + e, Map.of("title", "Hijacked"),
                () -> assertThat(jdbc.queryForObject("select title || amount from ledger_entries where id = ?", String.class, e)).isEqualTo(before));
        UUID r = entryB();
        long entries = count("select count(*) from ledger_entries where community_id = ?", communityB.getId());
        assertCrossTenantUpdate("POST", LEDGER + "/entries/" + r + "/reverse", Map.of("reason", "not yours"),
                () -> assertThat(count("select count(*) from ledger_entries where community_id = ?", communityB.getId())).isEqualTo(entries));
    }

    @Test
    void creatingEntriesNeverTargetsBOrUsesBsCategory() {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("type", "EXPENSE", "categoryId", categoryOf(sessionA, "Maintenance", "EXPENSE").toString(), "amount", "5.00", "entryDate", TODAY.toString(), "title", "A spend"));
        assertCreateCannotTargetOtherTenant(LEDGER + "/entries", body, r -> id(r.json()), LEDGER + "/entries/%s");
        body.put("categoryId", categoryOf(sessionB, "Maintenance", "EXPENSE").toString());
        long before = count("select count(*) from ledger_entries where community_id = ?", communityB.getId());
        ApiClient.Response foreign = asA("POST", LEDGER + "/entries", body);
        body.put("categoryId", UUID.randomUUID().toString());
        ApiClient.Response missing = asA("POST", LEDGER + "/entries", body);
        assertThat(foreign.status()).as(foreign.body()).isEqualTo(400);
        assertThat(foreign.json().get("errors")).as("a foreign category looks exactly like a missing one").isEqualTo(missing.json().get("errors"));
        assertThat(count("select count(*) from ledger_entries where community_id = ?", communityB.getId())).isEqualTo(before);
    }

    @Test
    void attachmentsSummaryAndReports() {
        assertTenantSingleton("POST", LEDGER + "/attachments/upload-url", Map.of("contentType", "image/png", "sizeBytes", 1000),
                () -> asB("GET", LEDGER + "/summary", null).body());
        UUID e = entryB();
        assertThat(e).isNotNull();
        assertTenantSingleton("GET", LEDGER + "/summary", null, () -> asB("GET", LEDGER + "/summary", null).body());
        assertThat(asA("GET", LEDGER + "/summary", null).body()).doesNotContain("B spend");
        assertTenantSingleton("GET", REPORTS + "/collection", null, () -> new String(api.getBytes(REPORTS + "/collection", "Authorization", sessionB.bearer()).body()));
    }

    @Test
    void aForeignAttachmentKeyIsRejected() {
        UUID e = entryB();
        String foreignKey = "communities/" + communityB.getId() + "/ledger/" + UUID.randomUUID() + ".png";
        storage.put(foreignKey, new byte[] {1}, "image/png");
        Map<String, Object> body = Map.of("type", "EXPENSE", "categoryId", categoryOf(sessionA, "Maintenance", "EXPENSE").toString(), "amount", "5.00",
                "entryDate", TODAY.toString(), "title", "steals file", "attachmentKey", foreignKey);
        assertThat(e).isNotNull();
        assertThat(asA("POST", LEDGER + "/entries", body).status()).isBetween(400, 404);
    }
}

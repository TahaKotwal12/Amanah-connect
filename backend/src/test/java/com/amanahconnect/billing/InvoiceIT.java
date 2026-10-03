package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.common.FinancialYear;
import com.amanahconnect.support.ApiClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class InvoiceIT extends AbstractFinanceIT {

    private static final String FY = FinancialYear.labelFor(TODAY, 4);

    private Map<String, Object> body(UUID member) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberId", member.toString());
        body.put("kind", "EVENT");
        body.put("description", "Annual day contribution");
        body.put("amount", "750.00");
        body.put("dueDate", TODAY.plusDays(14).toString());
        return body;
    }

    @Test
    void aOneOffInvoiceIsIssuedWithTheNextNumberAndEmailed() {
        String address = email();
        UUID member = member(sessionA, "Asha Rao", address, true);

        ApiClient.Response response = asA("POST", INVOICES, body(member));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode invoice = response.json();
        assertThat(invoice.get("invoiceNo").asString()).isEqualTo("INV-" + FY + "/000001");
        assertThat(invoice.get("status").asString()).isEqualTo("ISSUED");
        assertThat(invoice.get("kind").asString()).isEqualTo("EVENT");
        assertThat(invoice.get("amount").asString()).isEqualTo("750.00");
        assertThat(invoice.get("balance").asString()).isEqualTo("750.00");
        assertThat(invoice.get("memberName").asString()).isEqualTo("Asha Rao");
        assertThat(invoice.get("description").asString()).isEqualTo("Annual day contribution");
        assertThat(invoice.get("issuedOn").asString()).isEqualTo(TODAY.toString());
        assertThat(invoice.get("feePlanId").isNull()).isTrue();
        assertThat(outbox(address, "member-bill")).hasSize(1);
        assertThat(count("select count(*) from audit_logs where action = 'INVOICE_CREATED' and entity_id = ?", id(invoice))).isOne();
        assertThat(id(asA("POST", INVOICES, body(member)).json())).isNotNull();
        assertThat(jdbc.queryForObject("select max(invoice_no) from invoices where community_id = ?", String.class, communityA.getId())).isEqualTo("INV-" + FY + "/000002");
    }

    @Test
    void validatesAManualInvoice() {
        UUID member = memberA("Asha");
        for (String amount : new String[] {"0", "-1", "1.999", "x"}) {
            Map<String, Object> bad = body(member);
            bad.put("amount", amount);
            assertThat(asA("POST", INVOICES, bad).status()).as(amount).isEqualTo(400);
        }
        Map<String, Object> legacy = body(member);
        legacy.put("kind", "MEMBERSHIP");
        assertThat(asA("POST", INVOICES, legacy).status()).isEqualTo(400);
        Map<String, Object> noDescription = body(member);
        noDescription.put("description", " ");
        assertThat(asA("POST", INVOICES, noDescription).status()).isEqualTo(400);
        Map<String, Object> farFuture = body(member);
        farFuture.put("dueDate", TODAY.plusYears(6).toString());
        assertThat(asA("POST", INVOICES, farFuture).status()).isEqualTo(400);
        Map<String, Object> noDue = body(member);
        noDue.remove("dueDate");
        assertThat(asA("POST", INVOICES, noDue).status()).isEqualTo(400);
        assertThat(asA("POST", INVOICES, body(UUID.randomUUID())).status()).isEqualTo(404);
        UUID deleted = memberA("Deleted");
        asA("DELETE", "/api/v1/community/members/" + deleted, null);
        assertThat(asA("POST", INVOICES, body(deleted)).status()).isEqualTo(404);
        assertThat(count("select count(*) from invoices where community_id = ?", communityA.getId())).isZero();
        assertThat(count("select count(*) from document_counters where community_id = ? and counter_type = 'INVOICE' and last_value > 0", communityA.getId())).as("no number burned").isZero();
    }

    @Test
    void aDraftHasNoNumberIsEditableAndIssuedLater() {
        String address = email();
        UUID member = member(sessionA, "Asha", address, true);
        Map<String, Object> draftBody = body(member);
        draftBody.put("draft", true);

        JsonNode draft = asA("POST", INVOICES, draftBody).json();

        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.get("invoiceNo").isNull()).isTrue();
        assertThat(outbox(address, "member-bill")).as("no email for a draft").isEmpty();
        assertThat(asA("POST", INVOICES + "/" + id(draft) + "/payments", Map.of("amount", "10.00", "method", "CASH")).code()).isEqualTo("INVOICE_NOT_PAYABLE");

        JsonNode edited = asA("PATCH", INVOICES + "/" + id(draft), Map.of("amount", "800.00", "description", "Corrected", "dueDate", TODAY.plusDays(30).toString(), "kind", "FINE")).json();
        assertThat(edited.get("amount").asString()).isEqualTo("800.00");
        assertThat(edited.get("kind").asString()).isEqualTo("FINE");
        assertThat(edited.get("invoiceNo").isNull()).isTrue();

        ApiClient.Response issued = asA("POST", INVOICES + "/" + id(draft) + "/issue", null);
        assertThat(issued.status()).as(issued.body()).isEqualTo(200);
        assertThat(issued.json().get("status").asString()).isEqualTo("ISSUED");
        assertThat(issued.json().get("invoiceNo").asString()).isEqualTo("INV-" + FY + "/000001");
        assertThat(outbox(address, "member-bill")).hasSize(1);
        assertThat(asA("POST", INVOICES + "/" + id(draft) + "/issue", null).code()).as("only once").isEqualTo("INVALID_STATE_TRANSITION");
        ApiClient.Response noEdit = asA("PATCH", INVOICES + "/" + id(draft), Map.of("amount", "1.00"));
        assertThat(noEdit.status()).as("an issued invoice cannot be edited").isEqualTo(409);
        assertThat(count("select count(*) from audit_logs where entity_id = ? and action in ('INVOICE_CREATED', 'INVOICE_UPDATED', 'INVOICE_ISSUED')", id(draft))).isEqualTo(3);
    }

    @Test
    void issuingADraftWithAPastDueDateMakesItOverdueAndSendEmailFalseIsHonoured() {
        String address = email();
        UUID member = member(sessionA, "Asha", address, true);
        Map<String, Object> draftBody = body(member);
        draftBody.put("draft", true);
        draftBody.put("dueDate", TODAY.minusDays(2).toString());
        UUID draft = id(asA("POST", INVOICES, draftBody).json());

        JsonNode issued = asA("POST", INVOICES + "/" + draft + "/issue", Map.of("sendEmail", false)).json();

        assertThat(issued.get("status").asString()).isEqualTo("OVERDUE");
        assertThat(outbox(address, "member-bill")).isEmpty();
    }

    @Test
    void cancellingNeedsAReasonKeepsTheNumberAndStopsPaymentLinks() {
        UUID member = memberA("Asha");
        setUpi(sessionA);
        UUID invoiceId = id(asA("POST", INVOICES, body(member)).json());
        String link = asA("POST", INVOICES + "/" + invoiceId + "/pay-link", null).json().get("url").asString();
        String token = link.substring(link.lastIndexOf('/') + 1);
        assertThat(api.get("/api/v1/public/pay/" + token).status()).isEqualTo(200);

        assertThat(asA("POST", INVOICES + "/" + invoiceId + "/cancel", Map.of()).status()).isEqualTo(400);
        assertThat(asA("POST", INVOICES + "/" + invoiceId + "/cancel", Map.of("reason", " ")).status()).isEqualTo(400);
        ApiClient.Response cancelled = asA("POST", INVOICES + "/" + invoiceId + "/cancel", Map.of("reason", "  Raised by mistake "));

        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.json().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.json().get("cancelReason").asString()).isEqualTo("Raised by mistake");
        assertThat(cancelled.json().get("invoiceNo").asString()).as("the number stays: numbers are gap-free").isEqualTo("INV-" + FY + "/000001");
        assertThat(count("select count(*) from invoices where id = ?", invoiceId)).as("kept on record").isOne();
        assertThat(api.get("/api/v1/public/pay/" + token).status()).as("its payment link stops working").isEqualTo(404);
        assertThat(asA("POST", INVOICES + "/" + invoiceId + "/cancel", Map.of("reason", "again")).code()).isEqualTo("INVALID_STATE_TRANSITION");
        Map<String, Object> audit = jdbc.queryForMap("select after::text as a from audit_logs where action = 'INVOICE_CANCELLED' and entity_id = ?", invoiceId);
        assertThat(audit.get("a").toString()).contains("Raised by mistake");
        assertThat(jdbc.queryForObject("select cancelled_by from invoices where id = ?", UUID.class, invoiceId)).isEqualTo(adminA.id());
        assertThat(id(asA("POST", INVOICES, body(member)).json())).isNotNull();
        assertThat(jdbc.queryForObject("select max(invoice_no) from invoices where community_id = ?", String.class, communityA.getId())).as("the next one continues the sequence").isEqualTo("INV-" + FY + "/000002");
    }

    @Test
    void anInvoiceIsNeverDeletedAtTheDatabaseEither() {
        UUID invoiceId = id(invoiceA(memberA("Asha"), "100.00", TODAY.plusDays(3)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("delete from invoices where id = ?", invoiceId)).hasMessageContaining("never deleted");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update invoices set status = 'CANCELLED' where id = ?", invoiceId)).as("cancel needs a reason, even for SQL").hasMessageContaining("ck_invoices_cancel_reason");
    }

    @Test
    void listsFiltersSortsAndSearchesInvoices() {
        UUID asha = member(sessionA, "Asha Rao", email(), true);
        UUID ravi = member(sessionA, "Ravi Kumar", email(), true);
        JsonNode a1 = invoiceA(asha, "100.00", TODAY.plusDays(5));
        JsonNode a2 = invoiceA(asha, "300.00", TODAY.minusDays(5));
        JsonNode r1 = invoiceA(ravi, "200.00", TODAY.plusDays(9));
        payOk(sessionA, id(r1), "200.00");

        assertThat(ids(asA("GET", INVOICES + "?memberId=" + asha, null))).containsExactlyInAnyOrder(id(a1).toString(), id(a2).toString());
        assertThat(ids(asA("GET", INVOICES + "?status=PAID", null))).containsExactly(id(r1).toString());
        assertThat(ids(asA("GET", INVOICES + "?status=OVERDUE", null))).containsExactly(id(a2).toString());
        assertThat(ids(asA("GET", INVOICES + "?outstandingOnly=true", null))).containsExactlyInAnyOrder(id(a1).toString(), id(a2).toString());
        assertThat(ids(asA("GET", INVOICES + "?q=ravi", null))).containsExactly(id(r1).toString());
        assertThat(ids(asA("GET", INVOICES + "?q=" + a1.get("invoiceNo").asString(), null))).containsExactly(id(a1).toString());
        assertThat(ids(asA("GET", INVOICES + "?dueFrom=" + TODAY + "&dueTo=" + TODAY.plusDays(6), null))).containsExactly(id(a1).toString());
        assertThat(ids(asA("GET", INVOICES + "?kind=MAINTENANCE", null))).hasSize(3);
        assertThat(ids(asA("GET", INVOICES + "?kind=FINE", null))).isEmpty();
        assertThat(ids(asA("GET", INVOICES + "?sort=amount,desc", null))).containsExactly(id(a2).toString(), id(r1).toString(), id(a1).toString());
        assertThat(ids(asA("GET", INVOICES + "?sort=dueDate,asc", null)).get(0)).isEqualTo(id(a2).toString());
        assertThat(asA("GET", INVOICES + "?size=2&page=1", null).json().get("items").size()).isOne();
        assertThat(asA("GET", INVOICES + "?q=%25", null).json().get("total").asInt()).isZero();
        assertThat(asA("GET", INVOICES + "?status=BOGUS", null).status()).isEqualTo(400);
        assertThat(asA("GET", INVOICES + "?sort=memberId", null).status()).as("not whitelisted").isEqualTo(400);
        assertThat(asA("GET", INVOICES + "?dueFrom=yesterday", null).status()).isEqualTo(400);
    }

    private List<String> ids(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        response.json().get("items").forEach(n -> ids.add(n.get("id").asString()));
        return ids;
    }

    @Test
    void theInvoiceDetailShowsEveryPaymentRow() {
        UUID invoiceId = id(invoiceA(memberA("Asha"), "100.00", TODAY.plusDays(5)));
        payOk(sessionA, invoiceId, "30.00");

        JsonNode detail = invoiceView(sessionA, invoiceId);

        assertThat(detail.get("invoice").get("amountPaid").asString()).isEqualTo("30.00");
        assertThat(detail.get("payments").size()).isOne();
        assertThat(detail.get("payments").get(0).get("amount").asString()).isEqualTo("30.00");
        assertThat(detail.get("payments").get(0).get("kind").asString()).isEqualTo("PAYMENT");
        assertThat(detail.get("payments").get(0).get("receiptNo").asString()).startsWith("RCP-");
    }

    @Test
    void aSuspendedCommunityCannotCreateOrCancelInvoices() {
        UUID member = memberA("Asha");
        UUID invoiceId = id(invoiceA(member, "100.00", TODAY.plusDays(5)));
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());

        assertThat(asA("GET", INVOICES, null).status()).isEqualTo(200);
        assertThat(asA("POST", INVOICES, body(member)).code()).isEqualTo("COMMUNITY_SUSPENDED");
        assertThat(asA("POST", INVOICES + "/" + invoiceId + "/cancel", Map.of("reason", "x")).code()).isEqualTo("COMMUNITY_SUSPENDED");
        assertThat(asA("POST", INVOICES + "/generate", Map.of("feePlanId", UUID.randomUUID().toString())).code()).isEqualTo("COMMUNITY_SUSPENDED");
        assertThat(LocalDate.now()).isNotNull();
    }
}

package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Invoice totals for a filter, the preview of a billing run, and the reminders on an invoice's detail. */
class InvoiceInsightsIT extends AbstractFinanceIT {

    private JsonNode totals(String query) {
        ApiClient.Response response = asA("GET", INVOICES + "/totals" + query, null);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private JsonNode preview(UUID planId, String period) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("feePlanId", planId.toString());
        if (period != null) body.put("period", period);
        ApiClient.Response response = asA("POST", INVOICES + "/generate/preview", body);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    // ---- totals -----------------------------------------------------------------------------------------------------

    @Test
    void totalsCoverEverythingTheFilterMatchesNotOnePage() {
        UUID asha = memberA("Asha");
        for (int i = 0; i < 3; i++) invoiceA(asha, "100.10", TODAY.plusDays(10));
        invoiceA(memberA("Ravi"), "50.00", TODAY.plusDays(10));

        JsonNode all = totals("");

        assertThat(all.get("count").asLong()).isEqualTo(4);
        assertThat(all.get("amount").asString()).isEqualTo("350.30");
        assertThat(all.get("amountPaid").asString()).isEqualTo("0.00");
        assertThat(all.get("balance").asString()).isEqualTo("350.30");
        assertThat(all.get("amount").isString()).as("money is a string").isTrue();
        // The list is paged, the totals are not.
        assertThat(asA("GET", INVOICES + "?size=1", null).json().get("items")).hasSize(1);
    }

    @Test
    void totalsFollowPaymentsAndTheSameFiltersAsTheList() {
        UUID asha = memberA("Asha");
        UUID inv = id(invoiceA(asha, "1000.00", TODAY.plusDays(10)));
        invoiceA(memberA("Ravi"), "200.00", TODAY.plusDays(10));
        payOk(sessionA, inv, "300.00");

        JsonNode partial = totals("?status=PARTIAL");
        assertThat(partial.get("count").asLong()).isOne();
        assertThat(partial.get("amount").asString()).isEqualTo("1000.00");
        assertThat(partial.get("amountPaid").asString()).isEqualTo("300.00");
        assertThat(partial.get("balance").asString()).isEqualTo("700.00");

        assertThat(totals("?q=ravi").get("amount").asString()).isEqualTo("200.00");
        assertThat(totals("?memberId=" + asha).get("count").asLong()).isOne();
        assertThat(totals("?q=nobody-here").get("count").asLong()).isZero();
        assertThat(totals("?q=nobody-here").get("amount").asString()).isEqualTo("0.00");
    }

    @Test
    void cancelledInvoicesAreCountedButAddNothingUnlessAskedFor() {
        UUID asha = memberA("Asha");
        invoiceA(asha, "400.00", TODAY.plusDays(10));
        UUID cancelled = id(invoiceA(asha, "999.00", TODAY.plusDays(10)));
        asA("POST", INVOICES + "/" + cancelled + "/cancel", Map.of("reason", "wrong member"));

        JsonNode all = totals("");
        assertThat(all.get("count").asLong()).isEqualTo(2);
        assertThat(all.get("amount").asString()).isEqualTo("400.00");

        JsonNode onlyCancelled = totals("?status=CANCELLED");
        assertThat(onlyCancelled.get("count").asLong()).isOne();
        assertThat(onlyCancelled.get("amount").asString()).isEqualTo("999.00");
    }

    @Test
    void totalsOfOneCommunityNeverIncludeAnother() {
        invoiceA(memberA("Asha"), "100.00", TODAY.plusDays(10));
        invoice(sessionB, member(sessionB, "Bee", email(), true), "777.00", TODAY.plusDays(10));

        ApiClient.Response forA = assertTenantSingleton("GET", INVOICES + "/totals", null, () -> asB("GET", INVOICES + "/totals", null).body());

        assertThat(forA.json().get("amount").asString()).isEqualTo("100.00");
        assertThat(asB("GET", INVOICES + "/totals", null).json().get("amount").asString()).isEqualTo("777.00");
    }

    @Test
    void aBadFilterIsRefused() {
        assertThat(asA("GET", INVOICES + "/totals?status=NOPE", null).status()).isEqualTo(400);
    }

    // ---- preview ----------------------------------------------------------------------------------------------------

    @Test
    void previewsWhatBillingWouldDoWithoutDoingIt() {
        memberA("Asha");
        memberA("Ravi");
        memberA("Sunita");
        UUID inactive = memberA("Gone");
        asA("POST", "/api/v1/community/members/" + inactive + "/deactivate", Map.of("reason", "moved"));
        UUID plan = id(feePlan(sessionA, "Monthly maintenance", "1500.50", "MONTHLY", Map.of("dueDay", 5)));
        long invoicesBefore = count("select count(*) from invoices where community_id = ?", communityA.getId());
        long mailBefore = count("select count(*) from email_outbox where community_id = ?", communityA.getId());

        JsonNode result = preview(plan, null);

        assertThat(result.get("feePlanName").asString()).isEqualTo("Monthly maintenance");
        assertThat(result.get("eligibleMembers").asInt()).isEqualTo(3);
        assertThat(result.get("willCreate").asInt()).isEqualTo(3);
        assertThat(result.get("alreadyBilled").asInt()).isZero();
        assertThat(result.get("skippedInactive").asInt()).isEqualTo(1);
        assertThat(result.get("amountEach").asString()).isEqualTo("1500.50");
        assertThat(result.get("totalToBill").asString()).isEqualTo("4501.50");
        assertThat(count("select count(*) from invoices where community_id = ?", communityA.getId())).as("nothing created").isEqualTo(invoicesBefore);
        assertThat(count("select count(*) from email_outbox where community_id = ?", communityA.getId())).as("nothing emailed").isEqualTo(mailBefore);
        assertThat(count("select count(*) from audit_logs where action = 'INVOICES_GENERATED' and community_id = ?", communityA.getId())).isZero();
    }

    @Test
    void previewAfterBillingShowsEveryoneAsAlreadyBilledAndAgreesWithTheRealRun() {
        memberA("Asha");
        memberA("Ravi");
        UUID plan = id(feePlan(sessionA, "Monthly", "100.00", "MONTHLY", Map.of()));
        JsonNode forecast = preview(plan, null);

        JsonNode real = generate(sessionA, plan, null);

        assertThat(real.get("created").asInt()).isEqualTo(forecast.get("willCreate").asInt());
        assertThat(real.get("eligibleMembers").asInt()).isEqualTo(forecast.get("eligibleMembers").asInt());
        JsonNode after = preview(plan, null);
        assertThat(after.get("willCreate").asInt()).isZero();
        assertThat(after.get("alreadyBilled").asInt()).isEqualTo(2);
        assertThat(after.get("totalToBill").asString()).isEqualTo("0.00");
    }

    @Test
    void previewNeedsAPeriodAndDueDateForAOneTimePlanAndRefusesAnInactivePlan() {
        memberA("Asha");
        UUID oneTime = id(feePlan(sessionA, "Annual day", "250.00", "ONE_TIME", Map.of()));
        ApiClient.Response missing = asA("POST", INVOICES + "/generate/preview", Map.of("feePlanId", oneTime.toString()));
        assertThat(missing.status()).as(missing.body()).isEqualTo(400);

        UUID off = id(feePlan(sessionA, "Retired", "100.00", "MONTHLY", Map.of()));
        asA("PATCH", PLANS + "/" + off, Map.of("active", false));
        ApiClient.Response inactive = asA("POST", INVOICES + "/generate/preview", Map.of("feePlanId", off.toString()));
        assertThat(inactive.code()).isEqualTo("FEE_PLAN_INACTIVE");
    }

    @Test
    void previewOfAnotherCommunitysFeePlanIsNotFound() {
        UUID planOfB = id(feePlan(sessionB, "B plan", "100.00", "MONTHLY", Map.of()));

        assertCrossTenantUpdate("POST", INVOICES + "/generate/preview", Map.of("feePlanId", planOfB.toString()), () -> { });
    }

    // ---- reminders on the detail -------------------------------------------------------------------------------------

    @Test
    void theInvoiceDetailListsTheRemindersSentOldestFirst() {
        UUID inv = id(invoiceA(memberA("Asha"), "500.00", TODAY.plusDays(10)));
        assertThat(asA("GET", INVOICES + "/" + inv, null).json().get("reminders")).isEmpty();
        jdbc.update("insert into invoice_reminders (community_id, invoice_id, kind, reminder_date) values (?, ?, 'OVERDUE', ?)", communityA.getId(), inv, TODAY);
        jdbc.update("insert into invoice_reminders (community_id, invoice_id, kind, reminder_date) values (?, ?, 'DUE_SOON', ?)", communityA.getId(), inv, TODAY.minusDays(3));

        JsonNode reminders = asA("GET", INVOICES + "/" + inv, null).json().get("reminders");

        assertThat(reminders).hasSize(2);
        assertThat(reminders.get(0).get("kind").asString()).isEqualTo("DUE_SOON");
        assertThat(reminders.get(0).get("on").asString()).isEqualTo(TODAY.minusDays(3).toString());
        assertThat(reminders.get(1).get("kind").asString()).isEqualTo("OVERDUE");
    }
}

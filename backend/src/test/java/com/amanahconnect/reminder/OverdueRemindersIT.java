package com.amanahconnect.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.mail.AbstractMailIT;
import com.amanahconnect.support.ApiClient;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The defaulters list and "remind everyone who is overdue" on the community side. */
class OverdueRemindersIT extends AbstractMailIT {

    private static final String DEFAULTERS = "/api/v1/community/reminders/defaulters";
    private static final String REMIND = "/api/v1/community/reminders/overdue";

    private UUID payerA(String name, boolean consent) {
        return member(sessionA, name, email(), consent);
    }

    private UUID overdue(UUID member, String amount, int daysOverdue) {
        UUID id = id(invoiceA(member, amount, TODAY.plusDays(30)));
        jdbc.update("update invoices set issued_on = issued_on - 5, due_date = ?, status = 'OVERDUE' where id = ?", TODAY.minusDays(daysOverdue), id);
        return id;
    }

    @Test
    void listsMembersWhoOweMostFirstWithTheirTotalsAndOldestDueDate() {
        UUID asha = payerA("Asha", true);
        UUID ravi = payerA("Ravi", true);
        overdue(asha, "1000.00", 10);
        overdue(asha, "500.50", 40);
        overdue(ravi, "3000.00", 5);
        id(invoiceA(ravi, "999.00", TODAY.plusDays(20))); // not overdue

        ApiClient.Response response = asA("GET", DEFAULTERS, null);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode items = response.json().get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("fullName").asString()).isEqualTo("Ravi");
        assertThat(items.get(0).get("overdueAmount").asString()).isEqualTo("3000.00");
        assertThat(items.get(1).get("overdueInvoices").asInt()).isEqualTo(2);
        assertThat(items.get(1).get("overdueAmount").asString()).isEqualTo("1500.50");
        assertThat(items.get(1).get("oldestDueDate").asString()).isEqualTo(TODAY.minusDays(40).toString());
        assertThat(items.get(1).get("lastRemindedOn").isNull()).isTrue();
        assertThat(response.json().get("total").asLong()).isEqualTo(2);
    }

    @Test
    void remindsEveryOverdueInvoiceOnceAndANewPressTheSameDaySendsNothingMore() {
        UUID asha = payerA("Asha", true);
        UUID ravi = payerA("Ravi", true);
        UUID a1 = overdue(asha, "1000.00", 10);
        overdue(ravi, "3000.00", 5);

        JsonNode first = asA("POST", REMIND, null).json();
        assertThat(first.get("overdueInvoices").asInt()).isEqualTo(2);
        assertThat(first.get("queued").asInt()).isEqualTo(2);
        assertThat(first.get("alreadyRemindedToday").asInt()).isZero();
        assertThat(count("select count(*) from invoice_reminders where invoice_id = ? and kind = 'OVERDUE'", a1)).isOne();

        JsonNode again = asA("POST", REMIND, null).json();
        assertThat(again.get("queued").asInt()).isZero();
        assertThat(again.get("alreadyRemindedToday").asInt()).isEqualTo(2);
        assertThat(count("select count(*) from invoice_reminders where invoice_id = ? and kind = 'OVERDUE'", a1)).as("still one").isOne();

        assertThat(count("select count(*) from audit_logs where action = 'OVERDUE_REMINDERS_SENT' and community_id = ?", communityA.getId())).isEqualTo(2);
        // The defaulters list now says when they were reminded.
        assertThat(asA("GET", DEFAULTERS, null).json().get("items").get(0).get("lastRemindedOn").asString()).isEqualTo(TODAY.toString());
    }

    @Test
    void membersWhoCannotBeEmailedAreSkippedAndKeepTheirClaimFree() {
        UUID noConsent = payerA("No Consent", false);
        UUID inv = overdue(noConsent, "700.00", 8);

        JsonNode result = asA("POST", REMIND, null).json();

        assertThat(result.get("queued").asInt()).isZero();
        assertThat(result.get("skipped").asInt()).isEqualTo(1);
        assertThat(count("select count(*) from invoice_reminders where invoice_id = ?", inv)).as("released, so a later try is possible").isZero();
    }

    @Test
    void inactiveMembersAndPaidInvoicesAreLeftAlone() {
        UUID gone = payerA("Gone", true);
        overdue(gone, "400.00", 12);
        asA("POST", "/api/v1/community/members/" + gone + "/deactivate", java.util.Map.of("reason", "moved"));

        JsonNode result = asA("POST", REMIND, null).json();

        assertThat(result.get("overdueInvoices").asInt()).isZero();
        assertThat(result.get("queued").asInt()).isZero();
    }

    @Test
    void neverReachesAnotherCommunity() {
        UUID ofB = member(sessionB, "Bee", email(), true);
        UUID invoiceB = id(invoice(sessionB, ofB, "800.00", TODAY.plusDays(30)));
        jdbc.update("update invoices set issued_on = issued_on - 5, due_date = ?, status = 'OVERDUE' where id = ?", TODAY.minusDays(9), invoiceB);

        assertThat(asA("GET", DEFAULTERS, null).json().get("items")).isEmpty();
        assertThat(asA("POST", REMIND, null).json().get("overdueInvoices").asInt()).isZero();
        assertThat(count("select count(*) from invoice_reminders where invoice_id = ?", invoiceB)).isZero();
        assertThat(asB("GET", DEFAULTERS, null).json().get("items")).hasSize(1);
        assertTenantSingleton("GET", DEFAULTERS, null, () -> asB("GET", DEFAULTERS, null).body());
        assertTenantSingleton("POST", REMIND, null, () -> asB("GET", DEFAULTERS, null).body());
    }

    @Test
    void requiresSignIn() {
        assertThat(api.get(DEFAULTERS).status()).isEqualTo(401);
        assertThat(api.post(REMIND, null).status()).isEqualTo(401);
    }
}

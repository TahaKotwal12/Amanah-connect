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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Recording an expense, dividing it among members, billing them by email, and collecting cash. */
class ExpenseShareIT extends AbstractFinanceIT {

    private static final String EXPENSES = "/api/v1/community/expenses";

    /**
     * Dividing an expense queues a bill email per member. Leave none pending: other tests run the mail sender and read what it
     * did, and a full batch of my bills would crowd out the mail they queued.
     */
    @AfterEach
    void leaveNoPendingMail() {
        jdbc.update("update email_outbox set status = 'SENT', sent_at = now() where status = 'PENDING' and community_id in (?, ?)", communityA.getId(), communityB.getId());
    }

    private UUID expenseCategory(Session session) {
        for (JsonNode c : call(session, "GET", LEDGER + "/categories", null).json()) {
            if (c.get("type").asString().equals("EXPENSE")) return id(c);
        }
        throw new AssertionError("no expense category");
    }

    private JsonNode entry(Session session, String type, String amount, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("categoryId", type.equals("EXPENSE") ? expenseCategory(session).toString() : incomeCategory(session).toString());
        body.put("amount", amount);
        body.put("entryDate", TODAY.toString());
        body.put("title", title);
        ApiClient.Response response = call(session, "POST", LEDGER + "/entries", body);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json();
    }

    private UUID incomeCategory(Session session) {
        for (JsonNode c : call(session, "GET", LEDGER + "/categories", null).json()) {
            if (c.get("type").asString().equals("INCOME")) return id(c);
        }
        throw new AssertionError("no income category");
    }

    private UUID expenseA(String amount) {
        return id(entry(sessionA, "EXPENSE", amount, "Stairwell repair"));
    }

    private Map<String, Object> divideBody(List<UUID> members, LocalDate due) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (members != null) body.put("memberIds", members.stream().map(UUID::toString).toList());
        body.put("dueDate", due.toString());
        return body;
    }

    private ApiClient.Response divide(Session session, UUID expense, List<UUID> members) {
        return call(session, "POST", EXPENSES + "/" + expense + "/divide", divideBody(members, TODAY.plusDays(10)));
    }

    private ApiClient.Response cash(Session session, UUID invoice, String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("method", "CASH");
        return call(session, "POST", INVOICES + "/" + invoice + "/payments", body);
    }

    private JsonNode shares(Session session, UUID expense) {
        ApiClient.Response response = call(session, "GET", EXPENSES + "/" + expense + "/shares", null);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    // ---- dividing -------------------------------------------------------------------------------------------------------

    @Test
    void dividesAnExpenseEquallyAmongAllActiveMembersAndEmailsEachTheirBill() {
        String a = email(), b = email(), c = email();
        memberA("Asha"); // the helper creates members with their own addresses
        member(sessionA, "Ben", a, true);
        member(sessionA, "Chitra", b, true);
        member(sessionA, "Dev", c, true);
        UUID expense = expenseA("1000.00");

        ApiClient.Response response = divide(sessionA, expense, null);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.json().get("members").asInt()).isEqualTo(4);

        JsonNode view = shares(sessionA, expense);
        List<BigDecimal> amounts = new ArrayList<>();
        view.get("shares").forEach(s -> amounts.add(money(s.get("amount"))));
        assertThat(amounts).as("every paisa is accounted for").hasSize(4).allMatch(x -> x.compareTo(new BigDecimal("250.00")) == 0);
        assertThat(money(view.get("divided"))).isEqualByComparingTo("1000.00");
        assertThat(money(view.get("collected"))).isEqualByComparingTo("0.00");
        assertThat(money(view.get("pending"))).isEqualByComparingTo("1000.00");
        view.get("shares").forEach(s -> {
            assertThat(s.get("status").asString()).isEqualTo("ISSUED");
            assertThat(s.get("invoiceNo").asString()).startsWith("INV-");
        });
        // a bill email was queued for each member who has an address
        assertThat(outbox(a, "member-bill")).hasSize(1);
        assertThat(outbox(b, "member-bill")).hasSize(1);
        assertThat(outbox(c, "member-bill")).hasSize(1);
        assertThat(count("select count(*) from audit_logs where action = 'EXPENSE_DIVIDED' and community_id = ?", communityA.getId())).isOne();
    }

    @Test
    void anAmountThatDoesNotDivideEvenlyStillAddsUpExactly() {
        UUID m1 = memberA("One"), m2 = memberA("Two"), m3 = memberA("Three");
        UUID expense = expenseA("1000.00");
        assertThat(divide(sessionA, expense, List.of(m1, m2, m3)).status()).isEqualTo(201);

        JsonNode view = shares(sessionA, expense);
        BigDecimal sum = BigDecimal.ZERO;
        List<BigDecimal> amounts = new ArrayList<>();
        for (JsonNode s : view.get("shares")) {
            amounts.add(money(s.get("amount")));
            sum = sum.add(money(s.get("amount")));
        }
        assertThat(sum).isEqualByComparingTo("1000.00");
        assertThat(amounts).containsExactlyInAnyOrder(new BigDecimal("333.34"), new BigDecimal("333.33"), new BigDecimal("333.33"));
    }

    @Test
    void canBillJustSomeMembersOrJustOne() {
        UUID m1 = memberA("One"), m2 = memberA("Two");
        memberA("Three");
        UUID expense = expenseA("600.00");
        assertThat(divide(sessionA, expense, List.of(m1, m2)).json().get("members").asInt()).isEqualTo(2);
        assertThat(shares(sessionA, expense).get("shares")).hasSize(2);

        UUID other = expenseA("90.00");
        ApiClient.Response one = divide(sessionA, other, List.of(m1));
        assertThat(one.status()).isEqualTo(201);
        assertThat(money(shares(sessionA, other).get("shares").get(0).get("amount"))).isEqualByComparingTo("90.00");
    }

    @Test
    void anExpenseIsDividedOnlyOnceUntilItsSharesAreCancelled() {
        UUID m1 = memberA("One"), m2 = memberA("Two");
        UUID expense = expenseA("100.00");
        assertThat(divide(sessionA, expense, List.of(m1, m2)).status()).isEqualTo(201);

        ApiClient.Response again = divide(sessionA, expense, List.of(m1, m2));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("EXPENSE_ALREADY_DIVIDED");
        assertThat(count("select count(*) from invoices where expense_entry_id = ?", expense)).isEqualTo(2);

        for (JsonNode s : shares(sessionA, expense).get("shares")) {
            ApiClient.Response cancel = call(sessionA, "POST", INVOICES + "/" + s.get("invoiceId").asString() + "/cancel", Map.of("reason", "Divided by mistake"));
            assertThat(cancel.status()).as(cancel.body()).isEqualTo(200);
        }
        assertThat(divide(sessionA, expense, List.of(m1)).status()).as("after cancelling, it can be divided again").isEqualTo(201);
        assertThat(money(shares(sessionA, expense).get("divided"))).isEqualByComparingTo("100.00");
    }

    @Test
    void onlyARealCurrentExpenseCanBeDivided() {
        UUID m1 = memberA("One");
        UUID income = id(entry(sessionA, "INCOME", "100.00", "A grant"));
        ApiClient.Response onIncome = divide(sessionA, income, List.of(m1));
        assertThat(onIncome.status()).isEqualTo(409);
        assertThat(onIncome.code()).isEqualTo("INVALID_STATE_TRANSITION");

        UUID reversed = expenseA("50.00");
        assertThat(call(sessionA, "POST", LEDGER + "/entries/" + reversed + "/reverse", Map.of("reason", "Wrong amount")).status()).isEqualTo(201);
        assertThat(divide(sessionA, reversed, List.of(m1)).status()).as("a reversed expense").isEqualTo(409);
    }

    @Test
    void validatesTheRequest() {
        UUID m1 = memberA("One");
        UUID expense = expenseA("100.00");
        assertThat(call(sessionA, "POST", EXPENSES + "/" + expense + "/divide", Map.of()).status()).as("due date is required").isEqualTo(400);
        assertThat(call(sessionA, "POST", EXPENSES + "/" + expense + "/divide", divideBody(List.of(m1), TODAY.minusDays(1))).status()).as("not in the past").isEqualTo(400);
        UUID tiny = expenseA("0.01");
        UUID m2 = memberA("Two");
        assertThat(divide(sessionA, tiny, List.of(m1, m2)).status()).as("a paisa cannot be shared by two").isEqualTo(400);
        assertThat(count("select count(*) from invoices where expense_entry_id in (?, ?)", expense, tiny)).isZero();
    }

    @Test
    void inactiveMembersAreLeftOutOfAnEveryoneDivision() {
        UUID active = memberA("Active");
        UUID gone = memberA("Gone");
        assertThat(call(sessionA, "POST", "/api/v1/community/members/" + gone + "/deactivate", Map.of("reason", "Moved away")).status()).isIn(200, 204);
        UUID expense = expenseA("100.00");
        assertThat(divide(sessionA, expense, null).json().get("members").asInt()).isEqualTo(1);
        assertThat(shares(sessionA, expense).get("shares").get(0).get("memberId").asString()).isEqualTo(active.toString());
        assertThat(divide(sessionA, expenseA("10.00"), List.of(gone)).status()).as("naming an inactive member").isEqualTo(400);
    }

    // ---- cash and receipts ----------------------------------------------------------------------------------------------

    @Test
    void cashOnAShareIssuesAReceiptAndShowsUpAsCollected() {
        String address = email();
        UUID m1 = member(sessionA, "Payer", address, true);
        UUID m2 = memberA("Other");
        UUID expense = expenseA("500.00");
        divide(sessionA, expense, List.of(m1, m2));

        JsonNode first = null;
        for (JsonNode s : shares(sessionA, expense).get("shares")) {
            if (s.get("memberId").asString().equals(m1.toString())) first = s;
        }
        assertThat(first).isNotNull();
        ApiClient.Response paid = cash(sessionA, UUID.fromString(first.get("invoiceId").asString()), "250.00");
        assertThat(paid.status()).as(paid.body()).isEqualTo(201);
        assertThat(paid.json().get("payment").get("receiptNo").asString()).startsWith("RCP-");
        assertThat(outbox(address, "member-receipt")).as("the receipt is emailed").hasSize(1);

        JsonNode view = shares(sessionA, expense);
        assertThat(money(view.get("collected"))).isEqualByComparingTo("250.00");
        assertThat(money(view.get("pending"))).isEqualByComparingTo("250.00");
        assertThat(view.get("shares").get(0).get("status").asString()).isIn("PAID", "ISSUED");
    }

    @Test
    void noUpiLinkIsEmailedBecausePhaseOneIsCashOnlyWhenTheSwitchIsOff() {
        // The test profile keeps UPI on (its own tests need it); this checks the bill is built without a link when the community has no UPI set up.
        String address = email();
        UUID m1 = member(sessionA, "Payer", address, true);
        UUID expense = expenseA("100.00");
        divide(sessionA, expense, List.of(m1));
        assertThat(outbox(address, "member-bill").get(0).get("payload").toString()).contains("\"payLink\": null");
    }

    // ---- who may do this ------------------------------------------------------------------------------------------------

    @Test
    void anotherCommunityCannotSeeOrDivideThisExpense() {
        UUID m1 = memberA("One");
        UUID expense = expenseA("100.00");
        assertThat(divide(sessionB, expense, List.of(m1)).status()).isEqualTo(404);
        assertThat(call(sessionB, "GET", EXPENSES + "/" + expense + "/shares", null).status()).isEqualTo(404);
        assertThat(count("select count(*) from invoices where expense_entry_id = ?", expense)).isZero();
    }

    @Test
    void crossTenantReadAndDivideAreHiddenAndChangeNothing() {
        member(sessionB, "B member", email(), true); // so B's own division (the control) has someone to bill
        UUID expenseB = id(entry(sessionB, "EXPENSE", "100.00", "B's repair"));
        assertCrossTenantRead(EXPENSES + "/" + expenseB + "/shares");
        assertCrossTenantUpdate("POST", EXPENSES + "/" + expenseB + "/divide", divideBody(null, TODAY.plusDays(10)),
                () -> assertThat(count("select count(*) from invoices where expense_entry_id = ?", expenseB)).as("A's attempt billed nobody").isZero());
        assertThat(count("select count(*) from invoices where expense_entry_id = ?", expenseB)).as("B's own division worked").isOne();
    }

    @Test
    void aMemberOfAnotherCommunityCannotBeBilled() {
        UUID foreign = member(sessionB, "Foreign", email(), true);
        UUID expense = expenseA("100.00");
        ApiClient.Response response = divide(sessionA, expense, List.of(foreign));
        assertThat(response.status()).isEqualTo(404);
        assertThat(count("select count(*) from invoices where expense_entry_id = ?", expense)).isZero();
    }

    @Test
    void unknownExpenseIs404() {
        assertThat(divide(sessionA, UUID.randomUUID(), null).status()).isEqualTo(404);
        assertThat(call(sessionA, "GET", EXPENSES + "/" + UUID.randomUUID() + "/shares", null).status()).isEqualTo(404);
    }
}

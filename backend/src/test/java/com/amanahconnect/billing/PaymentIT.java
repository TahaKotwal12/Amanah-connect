package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.common.FinancialYear;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.TestData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Recording, reversing and receipting payments: the heart of the finance module. */
class PaymentIT extends AbstractFinanceIT {

    private static final String FY = FinancialYear.labelFor(TODAY, 4);

    @AfterEach
    void storageBack() {
        storage.failPuts = false;
    }

    private UUID invoiceFor(UUID member, String amount) {
        return id(invoiceA(member, amount, TODAY.plusDays(10)));
    }

    private Map<String, Object> payment(String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("method", "CASH");
        return body;
    }

    private BigDecimal ledgerNet(UUID invoiceOrNull) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from ledger_entries where community_id = ? and type = 'INCOME' and source = 'PAYMENT'", BigDecimal.class, communityA.getId());
    }

    private BigDecimal paymentsNet() {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from payment_records where community_id = ?", BigDecimal.class, communityA.getId());
    }

    private long receiptCounter(String fy) {
        Long value = jdbc.query("select last_value from document_counters where community_id = ? and counter_type = 'RECEIPT' and financial_year = ?", rs -> rs.next() ? rs.getLong(1) : 0L, communityA.getId(), fy);
        return value == null ? 0 : value;
    }

    // ---- partial, full ---------------------------------------------------------------------------------------------------

    @Test
    void aPartialThenAFullPaymentSettlesTheInvoiceWithReceiptsAndLedgerEntries() {
        String address = email();
        UUID member = member(sessionA, "Asha Rao", address, true);
        UUID invoiceId = invoiceFor(member, "1500.00");

        JsonNode first = payOk(sessionA, invoiceId, "500.00");
        JsonNode second = payOk(sessionA, invoiceId, "1000.00");

        assertThat(first.get("invoice").get("status").asString()).isEqualTo("PARTIAL");
        assertThat(first.get("invoice").get("amountPaid").asString()).isEqualTo("500.00");
        assertThat(first.get("invoice").get("balance").asString()).isEqualTo("1000.00");
        assertThat(second.get("invoice").get("status").asString()).isEqualTo("PAID");
        assertThat(second.get("invoice").get("balance").asString()).isEqualTo("0.00");
        assertThat(first.get("payment").get("receiptNo").asString()).isEqualTo("RCP-" + FY + "/000001");
        assertThat(second.get("payment").get("receiptNo").asString()).isEqualTo("RCP-" + FY + "/000002");
        assertThat(first.get("replayed").asBoolean()).isFalse();

        Map<String, Object> row = jdbc.queryForMap("select amount, amount_paid, status from invoices where id = ?", invoiceId);
        assertThat(row.get("amount_paid").toString()).isEqualTo("1500.00");
        assertThat(row.get("status")).isEqualTo("PAID");
        assertThat(count("select count(*) from receipts where community_id = ?", communityA.getId())).isEqualTo(2);

        List<Map<String, Object>> ledger = jdbc.queryForList("select e.amount, e.type, e.source, e.entry_date, c.system_key from ledger_entries e join ledger_categories c on c.id = e.category_id where e.community_id = ? order by e.created_at", communityA.getId());
        assertThat(ledger).hasSize(2);
        assertThat(ledger).allSatisfy(e -> {
            assertThat(e.get("type")).isEqualTo("INCOME");
            assertThat(e.get("source")).isEqualTo("PAYMENT");
            assertThat(e.get("system_key")).isEqualTo("MEMBERSHIP_FEES");
        });
        assertThat(ledger.stream().map(e -> e.get("amount").toString()).toList()).containsExactly("500.00", "1000.00");
        assertThat(ledgerNet(invoiceId)).isEqualByComparingTo("1500.00");

        assertThat(count("select count(*) from audit_logs where action = 'PAYMENT_RECORDED' and community_id = ?", communityA.getId())).isEqualTo(2);
        List<Map<String, Object>> mail = outbox(address, "member-receipt");
        assertThat(mail).hasSize(2);
        assertThat(mail.get(0).get("payload").toString()).contains("Rupees Five Hundred Only").contains("RCP-" + FY + "/000001").contains("\"balance\": \"1000.00\"");
        assertThat(jdbc.queryForObject("select emailed_at from receipts where receipt_no = ? and community_id = ?", java.sql.Timestamp.class, "RCP-" + FY + "/000001", communityA.getId())).isNotNull();
    }

    @Test
    void theDefaultsAndThePaymentDetailsAreRecorded() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "100.00");
        Map<String, Object> body = payment("40.00");
        body.put("method", "BANK");
        body.put("reference", "  NEFT 998877 ");
        body.put("receivedOn", TODAY.minusDays(3).toString());

        ApiClient.Response response = asA("POST", INVOICES + "/" + invoiceId + "/payments", body);

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        Map<String, Object> row = jdbc.queryForMap("select * from payment_records where invoice_id = ?", invoiceId);
        assertThat(row.get("method")).isEqualTo("BANK");
        assertThat(row.get("reference")).isEqualTo("NEFT 998877");
        assertThat(row.get("received_on").toString()).isEqualTo(TODAY.minusDays(3).toString());
        assertThat(row.get("recorded_by")).isEqualTo(adminA.id());
        assertThat(jdbc.queryForObject("select entry_date::text from ledger_entries where community_id = ?", String.class, communityA.getId())).as("the ledger uses the date money arrived").isEqualTo(TODAY.minusDays(3).toString());
        JsonNode defaulted = asA("POST", INVOICES + "/" + invoiceFor(memberA("Ravi"), "100.00") + "/payments", payment("10.00")).json();
        assertThat(defaulted.get("payment").get("receivedOn").asString()).isEqualTo(TODAY.toString());
    }

    // ---- overpayment and validation ---------------------------------------------------------------------------------------

    @Test
    void anOverpaymentIsRefusedAndLeavesNothingBehind() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "1500.00");

        ApiClient.Response over = asA("POST", INVOICES + "/" + invoiceId + "/payments", payment("1500.01"));

        assertThat(over.status()).isEqualTo(422);
        assertThat(over.code()).isEqualTo("OVERPAYMENT");
        assertThat(over.json().get("balance").asString()).isEqualTo("1500.00");
        assertThat(count("select count(*) from payment_records where community_id = ?", communityA.getId())).isZero();
        assertThat(count("select count(*) from receipts where community_id = ?", communityA.getId())).isZero();
        assertThat(count("select count(*) from ledger_entries where community_id = ?", communityA.getId())).isZero();
        assertThat(receiptCounter(FY)).as("no receipt number was burned").isZero();

        payOk(sessionA, invoiceId, "600.00");
        assertThat(asA("POST", INVOICES + "/" + invoiceId + "/payments", payment("900.01")).code()).isEqualTo("OVERPAYMENT");
        assertThat(asA("POST", INVOICES + "/" + invoiceId + "/payments", payment("900.00")).status()).as("exactly the balance is fine").isEqualTo(201);
        assertThat(jdbc.queryForObject("select status from invoices where id = ?", String.class, invoiceId)).isEqualTo("PAID");
    }

    @Test
    void validatesThePayment() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "100.00");
        String path = INVOICES + "/" + invoiceId + "/payments";
        for (String amount : new String[] {"0", "-5.00", "10.999", "abc", "1,000.00", "1 000"}) {
            assertThat(asA("POST", path, payment(amount)).status()).as(amount).isEqualTo(400);
        }
        Map<String, Object> noMethod = payment("10.00");
        noMethod.remove("method");
        assertThat(asA("POST", path, noMethod).status()).isEqualTo(400);
        Map<String, Object> badMethod = payment("10.00");
        badMethod.put("method", "BITCOIN");
        assertThat(asA("POST", path, badMethod).status()).isEqualTo(400);
        Map<String, Object> future = payment("10.00");
        future.put("receivedOn", TODAY.plusDays(1).toString());
        assertThat(asA("POST", path, future).status()).as("cannot be received tomorrow").isEqualTo(400);
        Map<String, Object> ancient = payment("10.00");
        ancient.put("receivedOn", TODAY.minusYears(11).toString());
        assertThat(asA("POST", path, ancient).status()).isEqualTo(400);
        Map<String, Object> longReference = payment("10.00");
        longReference.put("reference", "r".repeat(101));
        assertThat(asA("POST", path, longReference).status()).isEqualTo(400);
        assertThat(asA("POST", INVOICES + "/" + UUID.randomUUID() + "/payments", payment("10.00")).status()).isEqualTo(404);
        assertThat(count("select count(*) from payment_records where community_id = ?", communityA.getId())).isZero();
    }

    @Test
    void onlyOpenInvoicesTakePayments() {
        UUID member = memberA("Asha");
        UUID paid = invoiceFor(member, "50.00");
        payOk(sessionA, paid, "50.00");
        UUID cancelled = invoiceFor(member, "50.00");
        asA("POST", INVOICES + "/" + cancelled + "/cancel", Map.of("reason", "Mistake"));
        Map<String, Object> draftBody = new LinkedHashMap<>();
        draftBody.put("memberId", member.toString());
        draftBody.put("kind", "FINE");
        draftBody.put("description", "Draft");
        draftBody.put("amount", "50.00");
        draftBody.put("dueDate", TODAY.plusDays(5).toString());
        draftBody.put("draft", true);
        UUID draft = id(asA("POST", INVOICES, draftBody).json());

        for (UUID invoice : List.of(paid, cancelled, draft)) {
            ApiClient.Response response = asA("POST", INVOICES + "/" + invoice + "/payments", payment("10.00"));
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.code()).isEqualTo("INVOICE_NOT_PAYABLE");
        }
    }

    @Test
    void anOverdueInvoiceStaysOverdueWhilePartlyPaidAndTurnsPaidWhenSettled() {
        UUID invoiceId = id(invoiceA(memberA("Asha"), "1000.00", TODAY.minusDays(3)));
        assertThat(jdbc.queryForObject("select status from invoices where id = ?", String.class, invoiceId)).isEqualTo("OVERDUE");

        assertThat(payOk(sessionA, invoiceId, "400.00").get("invoice").get("status").asString()).as("late and partly paid").isEqualTo("OVERDUE");
        assertThat(payOk(sessionA, invoiceId, "600.00").get("invoice").get("status").asString()).isEqualTo("PAID");
    }

    // ---- idempotency --------------------------------------------------------------------------------------------------------

    @Test
    void theSameIdempotencyKeyAndRequestRecordsOnePayment() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "1000.00");
        Map<String, Object> body = payment("300.00");
        body.put("reference", "UTR-1");
        body.put("receivedOn", TODAY.toString());
        String key = "key-" + TestData.unique();

        ApiClient.Response first = call(sessionA, "POST", INVOICES + "/" + invoiceId + "/payments", body, "Idempotency-Key", key);
        ApiClient.Response second = call(sessionA, "POST", INVOICES + "/" + invoiceId + "/payments", body, "Idempotency-Key", key);
        ApiClient.Response third = call(sessionA, "POST", INVOICES + "/" + invoiceId + "/payments", body, "Idempotency-Key", key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).as("a retry gets the original result").isEqualTo(200);
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.json().get("replayed").asBoolean()).isTrue();
        assertThat(third.status()).isEqualTo(200);
        assertThat(second.json().get("payment").get("id")).isEqualTo(first.json().get("payment").get("id"));
        assertThat(second.json().get("payment").get("receiptNo")).isEqualTo(first.json().get("payment").get("receiptNo"));
        assertThat(count("select count(*) from payment_records where invoice_id = ?", invoiceId)).isOne();
        assertThat(count("select count(*) from receipts where community_id = ?", communityA.getId())).isOne();
        assertThat(count("select count(*) from ledger_entries where community_id = ?", communityA.getId())).isOne();
        assertThat(jdbc.queryForObject("select amount_paid from invoices where id = ?", BigDecimal.class, invoiceId)).isEqualByComparingTo("300.00");
        assertThat(receiptCounter(FY)).isOne();
    }

    @Test
    void theSameKeyWithADifferentRequestIsRefused() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "1000.00");
        UUID other = invoiceFor(memberA("Ravi"), "1000.00");
        String key = "key-" + TestData.unique();
        assertThat(pay(sessionA, invoiceId, "100.00", key).status()).isEqualTo(201);

        ApiClient.Response differentAmount = call(sessionA, "POST", INVOICES + "/" + invoiceId + "/payments", payment("200.00"), "Idempotency-Key", key);
        ApiClient.Response differentInvoice = call(sessionA, "POST", INVOICES + "/" + other + "/payments", payment("100.00"), "Idempotency-Key", key);

        assertThat(differentAmount.status()).isEqualTo(422);
        assertThat(differentAmount.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(differentInvoice.status()).isEqualTo(422);
        assertThat(count("select count(*) from payment_records where community_id = ?", communityA.getId())).isOne();
    }

    @Test
    void aMalformedKeyIsRefusedAndAnotherCommunityMayUseTheSameKey() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "100.00");
        for (String bad : new String[] {"short", "has space in it", "x".repeat(101), "bad/slash/key"}) {
            assertThat(call(sessionA, "POST", INVOICES + "/" + invoiceId + "/payments", payment("10.00"), "Idempotency-Key", bad).status()).as(bad).isEqualTo(400);
        }
        UUID b = invoice(sessionB, member(sessionB, "B member", email(), true), "100.00", TODAY.plusDays(5)).get("id").asString().isEmpty() ? null : UUID.randomUUID();
        assertThat(b).isNotNull();
        String key = "shared-key-" + TestData.unique();
        UUID invoiceB = id(invoice(sessionB, member(sessionB, "Another", email(), true), "100.00", TODAY.plusDays(5)));
        assertThat(pay(sessionA, invoiceId, "10.00", key).status()).isEqualTo(201);
        assertThat(pay(sessionB, invoiceB, "10.00", key).status()).as("keys are per community").isEqualTo(201);
    }

    @Test
    void concurrentRequestsWithOneKeyRecordOnePayment() throws Exception {
        UUID invoiceId = invoiceFor(memberA("Asha"), "1000.00");
        Map<String, Object> body = payment("250.00");
        String key = "race-" + TestData.unique();
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<ApiClient.Response>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(pool.submit(() -> call(sessionA, "POST", INVOICES + "/" + invoiceId + "/payments", body, "Idempotency-Key", key)));
            int created = 0, replayed = 0;
            for (var f : calls) {
                ApiClient.Response response = f.get();
                if (response.status() == 201) created++;
                else if (response.status() == 200) replayed++;
                else throw new AssertionError(response.status() + " " + response.body());
            }
            assertThat(created).isOne();
            assertThat(replayed).isEqualTo(7);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from payment_records where invoice_id = ?", invoiceId)).isOne();
    }

    // ---- two admins ------------------------------------------------------------------------------------------------------------

    @Test
    void twoAdminsCannotRecordTheSameMoneyTwice() throws Exception {
        UUID invoiceId = invoiceFor(memberA("Asha"), "1000.00");
        var second = users.extraAdminOf(communityA);
        Session otherAdmin = loginOk(second);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<ApiClient.Response> one = pool.submit(() -> call(sessionA, "POST", INVOICES + "/" + invoiceId + "/payments", payment("600.00")));
            Future<ApiClient.Response> two = pool.submit(() -> call(otherAdmin, "POST", INVOICES + "/" + invoiceId + "/payments", payment("600.00")));
            List<Integer> statuses = List.of(one.get().status(), two.get().status());
            assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select amount_paid from invoices where id = ?", BigDecimal.class, invoiceId)).isEqualByComparingTo("600.00");
        assertThat(count("select count(*) from payment_records where invoice_id = ?", invoiceId)).isOne();
        assertThat(count("select count(*) from receipts where community_id = ?", communityA.getId())).isOne();
    }

    @Test
    void manyConcurrentPaymentsOnOneInvoiceAddUpExactly() throws Exception {
        UUID invoiceId = invoiceFor(memberA("Asha"), "1000.00");
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) calls.add(pool.submit(() -> call(sessionA, "POST", INVOICES + "/" + invoiceId + "/payments", payment("100.00")).status()));
            int ok = 0, refused = 0;
            for (var f : calls) {
                int status = f.get();
                if (status == 201) ok++;
                else if (status == 422 || status == 409) refused++;
            }
            assertThat(ok).as("1000 / 100").isEqualTo(10);
            assertThat(refused).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select amount_paid from invoices where id = ?", BigDecimal.class, invoiceId)).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject("select status from invoices where id = ?", String.class, invoiceId)).isEqualTo("PAID");
    }

    @Test
    void aStaleExpectedVersionIsRefused() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "1000.00");
        long version = invoiceView(sessionA, invoiceId).get("invoice").get("version").asLong();
        Map<String, Object> body = payment("100.00");
        body.put("expectedVersion", version);
        assertThat(asA("POST", INVOICES + "/" + invoiceId + "/payments", body).status()).isEqualTo(201);

        ApiClient.Response stale = asA("POST", INVOICES + "/" + invoiceId + "/payments", body);

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(count("select count(*) from payment_records where invoice_id = ?", invoiceId)).isOne();
        body.put("expectedVersion", invoiceView(sessionA, invoiceId).get("invoice").get("version").asLong());
        assertThat(asA("POST", INVOICES + "/" + invoiceId + "/payments", body).status()).isEqualTo(201);
    }

    @Test
    void receiptNumbersStayGapFreeUnderConcurrentPayments() throws Exception {
        List<UUID> invoices = new ArrayList<>();
        for (int i = 0; i < 15; i++) invoices.add(invoiceFor(memberA("Payer " + i), "100.00"));
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> calls = new ArrayList<>();
            for (UUID invoice : invoices) calls.add(pool.submit(() -> call(sessionA, "POST", INVOICES + "/" + invoice + "/payments", payment("100.00")).status()));
            for (var f : calls) assertThat(f.get()).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }
        List<Integer> sequence = jdbc.queryForList("select substring(receipt_no from '[0-9]+$')::int from receipts where community_id = ? order by 1", Integer.class, communityA.getId());
        assertThat(sequence).hasSize(15);
        for (int i = 0; i < sequence.size(); i++) assertThat(sequence.get(i)).isEqualTo(i + 1);
        assertThat(receiptCounter(FY)).isEqualTo(15);
    }

    @Test
    void receiptsAreNumberedInTheFinancialYearTheMoneyArrivedIn() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "1000.00");
        LocalDate previousYear = LocalDate.of(FinancialYear.startOf(TODAY, 4).getYear(), 4, 1).minusDays(1);
        String previousFy = FinancialYear.labelFor(previousYear, 4);
        Map<String, Object> old = payment("100.00");
        old.put("receivedOn", previousYear.toString());

        JsonNode back = asA("POST", INVOICES + "/" + invoiceId + "/payments", old).json();
        JsonNode now = payOk(sessionA, invoiceId, "100.00");

        assertThat(back.get("payment").get("receiptNo").asString()).isEqualTo("RCP-" + previousFy + "/000001");
        assertThat(now.get("payment").get("receiptNo").asString()).as("each financial year has its own sequence").isEqualTo("RCP-" + FY + "/000001");
    }

    // ---- reversal ---------------------------------------------------------------------------------------------------------------

    @Test
    void reversingAPaymentOffsetsItEverywhereAndDeletesNothing() {
        String address = email();
        UUID member = member(sessionA, "Asha Rao", address, true);
        UUID invoiceId = invoiceFor(member, "1000.00");
        JsonNode first = payOk(sessionA, invoiceId, "400.00");
        JsonNode second = payOk(sessionA, invoiceId, "600.00");
        UUID secondPayment = id(second.get("payment"));
        assertThat(invoiceView(sessionA, invoiceId).get("invoice").get("status").asString()).isEqualTo("PAID");

        ApiClient.Response reversed = asA("POST", PAYMENTS + "/" + secondPayment + "/reverse", Map.of("reason", "Cheque bounced"));

        assertThat(reversed.status()).as(reversed.body()).isEqualTo(201);
        JsonNode reversal = reversed.json().get("payment");
        assertThat(reversal.get("kind").asString()).isEqualTo("REVERSAL");
        assertThat(reversal.get("amount").asString()).isEqualTo("-600.00");
        assertThat(reversal.get("reversedOf").asString()).isEqualTo(secondPayment.toString());
        assertThat(reversal.get("reversalReason").asString()).isEqualTo("Cheque bounced");
        assertThat(reversed.json().get("invoice").get("status").asString()).isEqualTo("PARTIAL");
        assertThat(reversed.json().get("invoice").get("amountPaid").asString()).isEqualTo("400.00");
        assertThat(reversed.json().get("invoice").get("balance").asString()).isEqualTo("600.00");

        assertThat(count("select count(*) from payment_records where invoice_id = ?", invoiceId)).as("nothing deleted, one row added").isEqualTo(3);
        assertThat(jdbc.queryForObject("select sum(amount) from payment_records where invoice_id = ?", BigDecimal.class, invoiceId)).isEqualByComparingTo("400.00");
        assertThat(ledgerNet(invoiceId)).as("the ledger nets the same").isEqualByComparingTo("400.00");
        assertThat(count("select count(*) from ledger_entries where community_id = ?", communityA.getId())).isEqualTo(3);
        Map<String, Object> ledgerReversal = jdbc.queryForMap("select amount, reversed_of, source, reversal_reason from ledger_entries where community_id = ? and reversed_of is not null", communityA.getId());
        assertThat(ledgerReversal.get("amount").toString()).isEqualTo("-600.00");
        assertThat(ledgerReversal.get("source")).isEqualTo("PAYMENT");
        assertThat(ledgerReversal.get("reversal_reason")).isEqualTo("Cheque bounced");

        assertThat(count("select count(*) from receipts where community_id = ?", communityA.getId())).as("receipts stay").isEqualTo(2);
        JsonNode receipt = asA("GET", RECEIPTS + "/" + second.get("payment").get("receiptId").asString(), null).json();
        assertThat(receipt.get("reversed").asBoolean()).isTrue();
        assertThat(receipt.get("reversalReason").asString()).isEqualTo("Cheque bounced");
        assertThat(asA("GET", RECEIPTS + "/" + first.get("payment").get("receiptId").asString(), null).json().get("reversed").asBoolean()).isFalse();
        assertThat(asA("GET", PAYMENTS + "/" + secondPayment, null).json().get("reversed").asBoolean()).isTrue();
        assertThat(count("select count(*) from audit_logs where action = 'PAYMENT_REVERSED' and community_id = ?", communityA.getId())).isOne();

        assertThat(payOk(sessionA, invoiceId, "600.00").get("invoice").get("status").asString()).as("it can be paid again").isEqualTo("PAID");
    }

    @Test
    void aReversedPaymentOnAnInvoicePastItsDueDateTurnsOverdue() {
        UUID invoiceId = id(invoiceA(memberA("Asha"), "100.00", TODAY.minusDays(1)));
        JsonNode paid = payOk(sessionA, invoiceId, "100.00");

        JsonNode reversed = asA("POST", PAYMENTS + "/" + id(paid.get("payment")) + "/reverse", Map.of("reason", "Wrong invoice")).json();

        assertThat(reversed.get("invoice").get("status").asString()).isEqualTo("OVERDUE");
        assertThat(reversed.get("invoice").get("amountPaid").asString()).isEqualTo("0.00");
    }

    @Test
    void aPaymentCanOnlyBeReversedOnce() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "100.00");
        UUID payment = id(payOk(sessionA, invoiceId, "100.00").get("payment"));
        JsonNode reversal = asA("POST", PAYMENTS + "/" + payment + "/reverse", Map.of("reason", "Mistake")).json().get("payment");

        ApiClient.Response again = asA("POST", PAYMENTS + "/" + payment + "/reverse", Map.of("reason", "Again"));
        ApiClient.Response ofReversal = asA("POST", PAYMENTS + "/" + id(reversal) + "/reverse", Map.of("reason", "Undo the undo"));

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ALREADY_REVERSED");
        assertThat(ofReversal.status()).isEqualTo(409);
        assertThat(ofReversal.code()).isEqualTo("ALREADY_REVERSED");
        assertThat(count("select count(*) from payment_records where invoice_id = ?", invoiceId)).isEqualTo(2);
    }

    @Test
    void reversalNeedsAReasonAndASaneDate() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "100.00");
        Map<String, Object> past = payment("100.00");
        past.put("receivedOn", TODAY.minusDays(5).toString());
        UUID payment = id(asA("POST", INVOICES + "/" + invoiceId + "/payments", past).json().get("payment"));

        assertThat(asA("POST", PAYMENTS + "/" + payment + "/reverse", Map.of()).status()).isEqualTo(400);
        assertThat(asA("POST", PAYMENTS + "/" + payment + "/reverse", Map.of("reason", "  ")).status()).isEqualTo(400);
        assertThat(asA("POST", PAYMENTS + "/" + payment + "/reverse", Map.of("reason", "x", "reversedOn", TODAY.minusDays(6).toString())).status()).as("before it was received").isEqualTo(400);
        assertThat(asA("POST", PAYMENTS + "/" + payment + "/reverse", Map.of("reason", "x", "reversedOn", TODAY.plusDays(1).toString())).status()).isEqualTo(400);
        assertThat(asA("POST", PAYMENTS + "/" + UUID.randomUUID() + "/reverse", Map.of("reason", "x")).status()).isEqualTo(404);
        ApiClient.Response ok = asA("POST", PAYMENTS + "/" + payment + "/reverse", Map.of("reason", "Entered twice", "reversedOn", TODAY.minusDays(2).toString()));
        assertThat(ok.status()).as(ok.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select entry_date::text from ledger_entries where community_id = ? and reversed_of is not null", String.class, communityA.getId())).isEqualTo(TODAY.minusDays(2).toString());
    }

    @Test
    void reversalsAreIdempotentToo() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "100.00");
        UUID payment = id(payOk(sessionA, invoiceId, "100.00").get("payment"));
        String key = "rev-" + TestData.unique();

        ApiClient.Response first = call(sessionA, "POST", PAYMENTS + "/" + payment + "/reverse", Map.of("reason", "Mistake"), "Idempotency-Key", key);
        ApiClient.Response second = call(sessionA, "POST", PAYMENTS + "/" + payment + "/reverse", Map.of("reason", "Mistake"), "Idempotency-Key", key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json().get("payment").get("id")).isEqualTo(first.json().get("payment").get("id"));
        assertThat(count("select count(*) from payment_records where invoice_id = ?", invoiceId)).isEqualTo(2);
    }

    @Test
    void anInvoiceCanBeCancelledOnlyAfterItsPaymentsAreReversed() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "100.00");
        UUID payment = id(payOk(sessionA, invoiceId, "40.00").get("payment"));

        ApiClient.Response blocked = asA("POST", INVOICES + "/" + invoiceId + "/cancel", Map.of("reason", "Wrong"));
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.code()).isEqualTo("INVOICE_NOT_PAYABLE");

        asA("POST", PAYMENTS + "/" + payment + "/reverse", Map.of("reason", "Refunded"));
        ApiClient.Response cancelled = asA("POST", INVOICES + "/" + invoiceId + "/cancel", Map.of("reason", "Wrong"));
        assertThat(cancelled.status()).as(cancelled.body()).isEqualTo(200);
        assertThat(cancelled.json().get("status").asString()).isEqualTo("CANCELLED");
    }

    // ---- ledger equals payments ------------------------------------------------------------------------------------------------

    @Test
    void ledgerTotalsEqualPaymentsNetOfReversals() {
        UUID m1 = memberA("Asha");
        UUID m2 = memberA("Ravi");
        UUID i1 = invoiceFor(m1, "1000.00");
        UUID i2 = invoiceFor(m2, "2500.50");
        payOk(sessionA, i1, "250.25");
        UUID toReverse = id(payOk(sessionA, i1, "749.75").get("payment"));
        payOk(sessionA, i2, "2500.50");
        asA("POST", PAYMENTS + "/" + toReverse + "/reverse", Map.of("reason", "Bounced"));
        asA("POST", DONATIONS, Map.of("donorName", "Anonymous", "amount", "111.11", "method", "CASH"));

        JsonNode summary = asA("GET", LEDGER + "/summary", null).json();

        BigDecimal payments = paymentsNet();
        assertThat(payments).isEqualByComparingTo("2861.86");
        assertThat(money(summary.get("totalIncome"))).isEqualByComparingTo(payments);
        assertThat(money(summary.get("totalExpense"))).isEqualByComparingTo("0");
        assertThat(money(summary.get("net"))).isEqualByComparingTo(payments);
        assertThat(count("select count(*) from ledger_entries e where e.community_id = ? and not exists (select 1 from payment_records p where p.id = e.source_id and p.community_id = e.community_id)", communityA.getId())).as("every entry has its payment").isZero();
    }

    // ---- receipts: PDF and email ------------------------------------------------------------------------------------------------

    @Test
    void theReceiptPdfHasEverythingAReceiptNeeds() throws Exception {
        UUID member = memberA("Asha Rao");
        jdbc.update("update communities set address_line1 = '12 Garden Road', city = 'Pune', contact_phone = '+91 98765 43210' where id = ?", communityA.getId());
        String logoKey = "communities/" + communityA.getId() + "/logo/" + UUID.randomUUID() + ".png";
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
        storage.put(logoKey, "image/png", png.size());
        jdbc.update("update communities set logo_key = ? where id = ?", logoKey, communityA.getId());
        UUID invoiceId = invoiceFor(member, "1234567.50");
        Map<String, Object> body = payment("1234567.50");
        body.put("method", "UPI");
        body.put("reference", "UTR998877");
        JsonNode result = asA("POST", INVOICES + "/" + invoiceId + "/payments", body).json();
        // the logo is fetched through the fake storage's content map, which only PDFs and put(bytes) fill
        storage.put(logoKey, "image/png", png.size());

        ApiClient.BinaryResponse pdf = bytesA(RECEIPTS + "/" + result.get("payment").get("receiptId").asString() + "/pdf");

        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.header("Content-Type")).startsWith("application/pdf");
        assertThat(pdf.header("Content-Disposition")).contains("inline").contains("receipt-RCP-");
        String text = pdfText(pdf.body());
        assertThat(text).contains(communityA.getName()).contains("12 Garden Road").contains("PAYMENT RECEIPT")
                .contains("RCP-" + FY + "/000001").contains("Asha Rao").contains(invoiceView(sessionA, invoiceId).get("invoice").get("invoiceNo").asString())
                .contains("INR 12,34,567.50").contains("Rupees Twelve Lakh Thirty-Four Thousand Five Hundred Sixty-Seven and Fifty Paise Only")
                .contains("UPI").contains("UTR998877").contains("Authorised signatory").doesNotContain("REVERSED");
    }

    @Test
    void theReceiptPdfIsStoredAndAReversedReceiptIsMarked() {
        UUID invoiceId = invoiceFor(memberA("Asha Rao"), "500.00");
        JsonNode paid = payOk(sessionA, invoiceId, "500.00");
        UUID receiptId = UUID.fromString(paid.get("payment").get("receiptId").asString());
        String key = jdbc.queryForObject("select pdf_key from receipts where id = ?", String.class, receiptId);
        assertThat(key).as("stored after the payment committed").isEqualTo("communities/" + communityA.getId() + "/receipts/" + receiptId + ".pdf");
        assertThat(storage.has(key)).isTrue();
        assertThat(bytesA(RECEIPTS + "/" + receiptId + "/pdf").body()).isEqualTo(storage.get(key).orElseThrow());

        asA("POST", PAYMENTS + "/" + id(paid.get("payment")) + "/reverse", Map.of("reason", "Cheque bounced"));

        String text = pdfText(bytesA(RECEIPTS + "/" + receiptId + "/pdf").body());
        assertThat(text).contains("REVERSED").contains("Cheque bounced").contains("RCP-" + FY + "/000001").contains("no longer valid");
        assertThat(pdfText(storage.get(key).orElseThrow())).as("the stored copy was refreshed too").contains("REVERSED");
    }

    @Test
    void aStorageOutageNeverFailsAPaymentAndThePdfIsStillServed() {
        storage.failPuts = true;
        UUID invoiceId = invoiceFor(memberA("Asha Rao"), "500.00");

        ApiClient.Response response = asA("POST", INVOICES + "/" + invoiceId + "/payments", payment("500.00"));

        assertThat(response.status()).as("the money is recorded").isEqualTo(201);
        UUID receiptId = UUID.fromString(response.json().get("payment").get("receiptId").asString());
        assertThat(jdbc.queryForObject("select pdf_key from receipts where id = ?", String.class, receiptId)).isNull();
        ApiClient.BinaryResponse pdf = bytesA(RECEIPTS + "/" + receiptId + "/pdf");
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdfText(pdf.body())).contains("PAYMENT RECEIPT");
        assertThat(count("select count(*) from ledger_entries where community_id = ?", communityA.getId())).isOne();
    }

    @Test
    void receiptEmailsRespectConsentTheSettingAndThePlan() {
        String consenting = email();
        String refusing = email();
        UUID yes = member(sessionA, "Consenting", consenting, true);
        UUID no = member(sessionA, "Refusing", refusing, false);
        UUID noAddress = member(sessionA, "No address", null, true);
        payOk(sessionA, invoiceFor(yes, "10.00"), "10.00");
        payOk(sessionA, invoiceFor(no, "10.00"), "10.00");
        payOk(sessionA, invoiceFor(noAddress, "10.00"), "10.00");
        assertThat(outbox(consenting, "member-receipt")).hasSize(1);
        assertThat(outbox(refusing, "member-receipt")).isEmpty();

        jdbc.update("update notification_settings set send_receipt = false where community_id = ?", communityA.getId());
        payOk(sessionA, invoiceFor(yes, "10.00"), "10.00");
        assertThat(outbox(consenting, "member-receipt")).as("switched off in settings").hasSize(1);

        jdbc.update("update notification_settings set send_receipt = true where community_id = ?", communityA.getId());
        Plan noReceiptMail = data.customPlan("No receipt mail", Map.of(), Map.of("upi_qr", true));
        jdbc.update("update communities set plan_id = ? where id = ?", noReceiptMail.getId(), communityA.getId());
        payOk(sessionA, invoiceFor(yes, "10.00"), "10.00");
        assertThat(outbox(consenting, "member-receipt")).as("not in the plan").hasSize(1);
    }

    @Test
    void receiptsAndBillsCanBeResentWithAnAddress() {
        String address = email();
        UUID member = member(sessionA, "Asha", address, true);
        UUID invoiceId = invoiceFor(member, "100.00");
        JsonNode paid = payOk(sessionA, invoiceId, "50.00");
        UUID receiptId = UUID.fromString(paid.get("payment").get("receiptId").asString());

        assertThat(asA("POST", RECEIPTS + "/" + receiptId + "/resend", null).status()).isEqualTo(202);
        assertThat(outbox(address, "member-receipt")).hasSize(2);
        assertThat(asA("POST", INVOICES + "/" + invoiceId + "/resend", null).status()).isEqualTo(202);
        assertThat(outbox(address, "member-bill")).hasSize(2);

        UUID noMail = member(sessionA, "No mail", null, true);
        UUID noMailInvoice = invoiceFor(noMail, "100.00");
        JsonNode noMailPaid = payOk(sessionA, noMailInvoice, "10.00");
        assertThat(asA("POST", INVOICES + "/" + noMailInvoice + "/resend", null).code()).isEqualTo("MEMBER_NOT_EMAILABLE");
        assertThat(asA("POST", RECEIPTS + "/" + noMailPaid.get("payment").get("receiptId").asString() + "/resend", null).code()).isEqualTo("MEMBER_NOT_EMAILABLE");

        asA("POST", PAYMENTS + "/" + id(paid.get("payment")) + "/reverse", Map.of("reason", "x"));
        ApiClient.Response reversed = asA("POST", RECEIPTS + "/" + receiptId + "/resend", null);
        assertThat(reversed.status()).isEqualTo(409);
        payOk(sessionA, invoiceId, "100.00");
        assertThat(asA("POST", INVOICES + "/" + invoiceId + "/resend", null).code()).as("a paid invoice has nothing to remind about").isEqualTo("INVOICE_NOT_PAYABLE");
    }

    // ---- donations ---------------------------------------------------------------------------------------------------------------

    @Test
    void recordsDonationsFromMembersAndAnonymousDonors() {
        String address = email();
        UUID member = member(sessionA, "Generous Member", address, true);

        JsonNode fromMember = asA("POST", DONATIONS, Map.of("memberId", member.toString(), "amount", "501.00", "method", "UPI", "reference", "UTR1")).json();
        JsonNode anonymous = asA("POST", DONATIONS, Map.of("donorName", "  A Well-wisher ", "amount", "1000.00", "method", "CASH")).json();

        assertThat(fromMember.get("payment").get("kind").asString()).isEqualTo("DONATION");
        assertThat(fromMember.get("payment").get("payerName").asString()).isEqualTo("Generous Member");
        assertThat(fromMember.get("invoice").isNull()).isTrue();
        assertThat(anonymous.get("payment").get("payerName").asString()).isEqualTo("A Well-wisher");
        assertThat(anonymous.get("payment").get("memberId").isNull()).isTrue();
        assertThat(fromMember.get("payment").get("receiptNo").asString()).isEqualTo("RCP-" + FY + "/000001");
        assertThat(anonymous.get("payment").get("receiptNo").asString()).isEqualTo("RCP-" + FY + "/000002");
        List<Map<String, Object>> ledger = jdbc.queryForList("select e.amount, c.system_key, e.title from ledger_entries e join ledger_categories c on c.id = e.category_id where e.community_id = ? order by e.amount", communityA.getId());
        assertThat(ledger).hasSize(2);
        assertThat(ledger).allSatisfy(e -> assertThat(e.get("system_key")).isEqualTo("DONATIONS"));
        assertThat(outbox(address, "member-receipt")).as("members get a receipt email, anonymous donors have no address").hasSize(1);
        assertThat(pdfText(bytesA(RECEIPTS + "/" + anonymous.get("payment").get("receiptId").asString() + "/pdf").body())).contains("A Well-wisher").contains("Donation");

        ApiClient.Response reversed = asA("POST", PAYMENTS + "/" + id(anonymous.get("payment")) + "/reverse", Map.of("reason", "Entered twice"));
        assertThat(reversed.status()).isEqualTo(201);
        assertThat(reversed.json().get("invoice").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select coalesce(sum(amount), 0) from ledger_entries where community_id = ?", BigDecimal.class, communityA.getId())).isEqualByComparingTo("501.00");
    }

    @Test
    void aDonationNeedsExactlyOneOfMemberAndDonorAndSaneValues() {
        UUID member = memberA("Asha");
        assertThat(asA("POST", DONATIONS, Map.of("amount", "10.00", "method", "CASH")).status()).isEqualTo(400);
        assertThat(asA("POST", DONATIONS, Map.of("memberId", member.toString(), "donorName", "Both", "amount", "10.00", "method", "CASH")).status()).isEqualTo(400);
        assertThat(asA("POST", DONATIONS, Map.of("donorName", "  ", "amount", "10.00", "method", "CASH")).status()).isEqualTo(400);
        assertThat(asA("POST", DONATIONS, Map.of("donorName", "X", "amount", "0", "method", "CASH")).status()).isEqualTo(400);
        assertThat(asA("POST", DONATIONS, Map.of("donorName", "X", "amount", "10.00")).status()).isEqualTo(400);
        assertThat(asA("POST", DONATIONS, Map.of("memberId", UUID.randomUUID().toString(), "amount", "10.00", "method", "CASH")).status()).isEqualTo(404);
        UUID deleted = memberA("Deleted");
        asA("DELETE", "/api/v1/community/members/" + deleted, null);
        assertThat(asA("POST", DONATIONS, Map.of("memberId", deleted.toString(), "amount", "10.00", "method", "CASH")).status()).isEqualTo(404);
        assertThat(count("select count(*) from payment_records where community_id = ?", communityA.getId())).isZero();
    }

    @Test
    void donationsAreIdempotent() {
        String key = "don-" + TestData.unique();
        Map<String, Object> body = Map.of("donorName", "Donor", "amount", "100.00", "method", "CASH");

        ApiClient.Response first = call(sessionA, "POST", DONATIONS, body, "Idempotency-Key", key);
        ApiClient.Response second = call(sessionA, "POST", DONATIONS, body, "Idempotency-Key", key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(count("select count(*) from payment_records where community_id = ?", communityA.getId())).isOne();
        assertThat(call(sessionA, "POST", DONATIONS, Map.of("donorName", "Donor", "amount", "200.00", "method", "CASH"), "Idempotency-Key", key).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    // ---- reading ---------------------------------------------------------------------------------------------------------------------

    @Test
    void listsPaymentsAndReceiptsWithFilters() {
        UUID member = memberA("Asha Rao");
        UUID invoiceId = invoiceFor(member, "1000.00");
        Map<String, Object> cash = payment("100.00");
        cash.put("receivedOn", TODAY.minusDays(10).toString());
        JsonNode p1 = asA("POST", INVOICES + "/" + invoiceId + "/payments", cash).json();
        Map<String, Object> upi = payment("200.00");
        upi.put("method", "UPI");
        JsonNode p2 = asA("POST", INVOICES + "/" + invoiceId + "/payments", upi).json();
        asA("POST", DONATIONS, Map.of("donorName", "Donor", "amount", "50.00", "method", "CASH"));
        asA("POST", PAYMENTS + "/" + id(p2.get("payment")) + "/reverse", Map.of("reason", "x"));

        assertThat(ids(asA("GET", PAYMENTS + "?kind=PAYMENT", null))).containsExactlyInAnyOrder(id(p1.get("payment")).toString(), id(p2.get("payment")).toString());
        assertThat(asA("GET", PAYMENTS + "?kind=REVERSAL", null).json().get("total").asInt()).isOne();
        assertThat(asA("GET", PAYMENTS + "?kind=DONATION", null).json().get("total").asInt()).isOne();
        assertThat(ids(asA("GET", PAYMENTS + "?method=CASH&kind=PAYMENT", null))).containsExactly(id(p1.get("payment")).toString());
        assertThat(ids(asA("GET", PAYMENTS + "?to=" + TODAY.minusDays(5) + "&kind=PAYMENT", null))).containsExactly(id(p1.get("payment")).toString());
        assertThat(asA("GET", PAYMENTS + "?invoiceId=" + invoiceId, null).json().get("total").asInt()).isEqualTo(3);
        assertThat(asA("GET", PAYMENTS + "?memberId=" + member, null).json().get("total").asInt()).isEqualTo(3);
        assertThat(asA("GET", PAYMENTS + "?kind=NOPE", null).status()).isEqualTo(400);
        assertThat(asA("GET", PAYMENTS + "?sort=reference", null).status()).isEqualTo(400);

        assertThat(asA("GET", RECEIPTS, null).json().get("total").asInt()).isEqualTo(3);
        assertThat(asA("GET", RECEIPTS + "?reversed=true", null).json().get("total").asInt()).isOne();
        assertThat(asA("GET", RECEIPTS + "?reversed=false", null).json().get("total").asInt()).isEqualTo(2);
        assertThat(asA("GET", RECEIPTS + "?q=asha", null).json().get("total").asInt()).isEqualTo(2);
        assertThat(asA("GET", RECEIPTS + "?q=donor", null).json().get("total").asInt()).isOne();
        assertThat(asA("GET", RECEIPTS + "?q=%25", null).json().get("total").asInt()).as("wildcards are plain characters").isZero();
        JsonNode detail = invoiceView(sessionA, invoiceId);
        assertThat(detail.get("payments").size()).isEqualTo(3);
        assertThat(detail.get("payments").get(2).get("kind").asString()).isEqualTo("REVERSAL");
    }

    private List<String> ids(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        response.json().get("items").forEach(n -> ids.add(n.get("id").asString()));
        return ids;
    }

    @Test
    void aSuspendedCommunityCannotRecordMoney() {
        UUID invoiceId = invoiceFor(memberA("Asha"), "100.00");
        JsonNode paid = payOk(sessionA, invoiceId, "10.00");
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());

        assertThat(asA("GET", INVOICES + "/" + invoiceId, null).status()).isEqualTo(200);
        assertThat(asA("POST", INVOICES + "/" + invoiceId + "/payments", payment("10.00")).code()).isEqualTo("COMMUNITY_SUSPENDED");
        assertThat(asA("POST", PAYMENTS + "/" + id(paid.get("payment")) + "/reverse", Map.of("reason", "x")).code()).isEqualTo("COMMUNITY_SUSPENDED");
        assertThat(asA("POST", DONATIONS, Map.of("donorName", "X", "amount", "1.00", "method", "CASH")).code()).isEqualTo("COMMUNITY_SUSPENDED");
        assertThat(asA("GET", RECEIPTS + "/" + paid.get("payment").get("receiptId").asString() + "/pdf", null).status()).as("receipts stay readable").isEqualTo(200);
    }
}

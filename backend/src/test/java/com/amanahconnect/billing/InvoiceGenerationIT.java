package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.ApiClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class InvoiceGenerationIT extends AbstractFinanceIT {

    private static final String PERIOD = "%d-%02d".formatted(TODAY.getYear(), TODAY.getMonthValue());

    private JsonNode plan(String name, String frequency, Map<String, Object> extra) {
        return feePlan(sessionA, name, "1500.00", frequency, extra);
    }

    private List<String> invoiceNos(UUID planId) {
        return jdbc.queryForList("select invoice_no from invoices where fee_plan_id = ? order by invoice_no", String.class, planId);
    }

    private long counter() {
        Long value = jdbc.query("select last_value from document_counters where community_id = ? and counter_type = 'INVOICE'", rs -> rs.next() ? rs.getLong(1) : 0L, communityA.getId());
        return value == null ? 0 : value;
    }

    @Test
    void billsEveryActiveMemberOnceWithGapFreeNumbersAndSkipsTheRest() {
        UUID m1 = memberA("Asha");
        UUID m2 = memberA("Ravi");
        UUID m3 = memberA("Sunita");
        UUID inactive = memberA("Gone Quiet");
        asA("POST", "/api/v1/community/members/" + inactive + "/deactivate", Map.of("reason", "moved"));
        UUID deleted = memberA("Deleted");
        asA("DELETE", "/api/v1/community/members/" + deleted, null);
        JsonNode plan = plan("Monthly maintenance", "MONTHLY", Map.of("dueDay", 5));

        ApiClient.Response response = asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString()));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode result = response.json();
        assertThat(result.get("period").asString()).isEqualTo(PERIOD);
        assertThat(result.get("eligibleMembers").asInt()).isEqualTo(3);
        assertThat(result.get("created").asInt()).isEqualTo(3);
        assertThat(result.get("alreadyBilled").asInt()).isZero();
        assertThat(result.get("dueDate").asString()).isEqualTo(LocalDate.of(TODAY.getYear(), TODAY.getMonthValue(), 5).toString());

        List<Map<String, Object>> rows = jdbc.queryForList("select * from invoices where fee_plan_id = ? order by invoice_no", id(plan));
        assertThat(rows).hasSize(3);
        String fy = com.amanahconnect.common.FinancialYear.labelFor(TODAY, 4);
        assertThat(rows.stream().map(r -> r.get("invoice_no").toString()).toList()).containsExactly("INV-" + fy + "/000001", "INV-" + fy + "/000002", "INV-" + fy + "/000003");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("kind")).isEqualTo("MAINTENANCE");
            assertThat(r.get("amount").toString()).isEqualTo("1500.00");
            assertThat(r.get("amount_paid").toString()).isEqualTo("0.00");
            assertThat(r.get("period")).isEqualTo(PERIOD);
            assertThat(r.get("description")).isEqualTo("Monthly maintenance");
        });
        assertThat(rows.stream().map(r -> r.get("member_id")).toList()).containsExactlyInAnyOrder(m1, m2, m3);
        assertThat(result.get("firstInvoiceNo").asString()).isEqualTo("INV-" + fy + "/000001");
        assertThat(result.get("lastInvoiceNo").asString()).isEqualTo("INV-" + fy + "/000003");
        assertThat(count("select count(*) from audit_logs where action = 'INVOICES_GENERATED' and community_id = ?", communityA.getId())).isOne();
    }

    @Test
    void generatingTheSamePeriodAgainBillsNobodyAndBurnsNoNumbers() {
        memberA("Asha");
        memberA("Ravi");
        JsonNode plan = plan("Idempotent", "MONTHLY", Map.of());
        generate(sessionA, id(plan), null);
        long numberAfterFirst = counter();
        long emailsAfterFirst = count("select count(*) from email_outbox where community_id = ? and template = 'member-bill'", communityA.getId());

        ApiClient.Response again = asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString(), "period", PERIOD));

        assertThat(again.status()).as("nothing new").isEqualTo(200);
        assertThat(again.json().get("created").asInt()).isZero();
        assertThat(again.json().get("alreadyBilled").asInt()).isEqualTo(2);
        assertThat(invoiceNos(id(plan))).hasSize(2);
        assertThat(counter()).as("no invoice number was used up").isEqualTo(numberAfterFirst);
        assertThat(count("select count(*) from email_outbox where community_id = ? and template = 'member-bill'", communityA.getId())).as("and nobody was emailed twice").isEqualTo(emailsAfterFirst);
    }

    @Test
    void aMemberWhoJoinsLaterIsBilledByTheNextCallOnly() {
        memberA("Asha");
        JsonNode plan = plan("Late joiner", "MONTHLY", Map.of());
        generate(sessionA, id(plan), null);
        UUID late = memberA("Late Joiner");

        JsonNode result = generate(sessionA, id(plan), null);

        assertThat(result.get("created").asInt()).isOne();
        assertThat(result.get("alreadyBilled").asInt()).isOne();
        assertThat(invoiceNos(id(plan))).hasSize(2);
        assertThat(count("select count(*) from invoices where fee_plan_id = ? and member_id = ?", id(plan), late)).isOne();
        assertThat(result.get("firstInvoiceNo").asString()).endsWith("/000002");
    }

    @Test
    void concurrentGenerationsBillEachMemberExactlyOnce() throws Exception {
        for (int i = 0; i < 12; i++) memberA("Member " + i);
        JsonNode plan = plan("Race", "MONTHLY", Map.of());
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<ApiClient.Response>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) calls.add(pool.submit(() -> asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString()))));
            int created = 0;
            for (var f : calls) {
                ApiClient.Response response = f.get();
                assertThat(response.status()).as(response.body()).isBetween(200, 201);
                created += response.json().get("created").asInt();
            }
            assertThat(created).as("across all six calls").isEqualTo(12);
        } finally {
            pool.shutdownNow();
        }
        List<String> numbers = invoiceNos(id(plan));
        assertThat(numbers).hasSize(12).doesNotHaveDuplicates();
        assertThat(count("select count(distinct member_id) from invoices where fee_plan_id = ?", id(plan))).isEqualTo(12);
        assertThat(counter()).as("the counter equals the invoices: no gaps").isEqualTo(12);
    }

    @Test
    void invoiceNumbersStayGapFreeWhenManyThingsIssueInvoicesAtOnce() throws Exception {
        List<UUID> members = new ArrayList<>();
        for (int i = 0; i < 10; i++) members.add(memberA("Concurrent " + i));
        JsonNode plan = plan("Mixed", "MONTHLY", Map.of());
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> calls = new ArrayList<>();
            calls.add(pool.submit(() -> asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString())).status()));
            for (UUID member : members) {
                calls.add(pool.submit(() -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("memberId", member.toString());
                    body.put("kind", "EVENT");
                    body.put("description", "One-off");
                    body.put("amount", "100.00");
                    body.put("dueDate", TODAY.plusDays(3).toString());
                    return asA("POST", INVOICES, body).status();
                }));
            }
            for (var f : calls) assertThat(f.get()).isBetween(200, 201);
        } finally {
            pool.shutdownNow();
        }
        List<Integer> sequence = jdbc.queryForList("select substring(invoice_no from '[0-9]+$')::int from invoices where community_id = ? order by 1", Integer.class, communityA.getId());
        assertThat(sequence).hasSize(20);
        for (int i = 0; i < sequence.size(); i++) assertThat(sequence.get(i)).as("position %d", i).isEqualTo(i + 1);
    }

    @Test
    void groupAndSelectedAudiences() {
        UUID a1 = member(sessionA, "In group", email(), true);
        UUID a2 = member(sessionA, "Also in group", email(), true);
        UUID other = memberA("Other group");
        asA("PATCH", "/api/v1/community/members/" + a1, Map.of("group", "Block A"));
        asA("PATCH", "/api/v1/community/members/" + a2, Map.of("group", "block a"));
        asA("PATCH", "/api/v1/community/members/" + other, Map.of("group", "Block B"));

        JsonNode groupPlan = plan("Block A only", "MONTHLY", Map.of("appliesTo", "GROUP", "group", "BLOCK A"));
        JsonNode groupResult = generate(sessionA, id(groupPlan), null);
        assertThat(groupResult.get("created").asInt()).as("case does not matter").isEqualTo(2);
        assertThat(jdbc.queryForList("select member_id from invoices where fee_plan_id = ?", UUID.class, id(groupPlan))).containsExactlyInAnyOrder(a1, a2);

        UUID inactive = memberA("Selected but inactive");
        asA("POST", "/api/v1/community/members/" + inactive + "/deactivate", Map.of("reason", "x"));
        JsonNode selectedPlan = plan("Selected", "MONTHLY", Map.of("appliesTo", "SELECTED", "memberIds", List.of(other.toString(), inactive.toString())));
        JsonNode selected = generate(sessionA, id(selectedPlan), null);
        assertThat(selected.get("created").asInt()).isOne();
        assertThat(selected.get("skippedInactive").asInt()).isOne();
        assertThat(jdbc.queryForList("select member_id from invoices where fee_plan_id = ?", UUID.class, id(selectedPlan))).containsExactly(other);
    }

    // ---- periods and due dates ----------------------------------------------------------------------------------------

    @Test
    void periodsAndDueDatesFollowTheFrequency() {
        memberA("Asha");
        JsonNode quarterly = plan("Quarterly", "QUARTERLY", Map.of("dueDay", 15));
        JsonNode q = generate(sessionA, id(quarterly), "2027-Q2");
        assertThat(q.get("period").asString()).isEqualTo("2027-Q2");
        assertThat(q.get("dueDate").asString()).isEqualTo("2027-04-15");

        JsonNode yearly = plan("Yearly", "YEARLY", Map.of());
        JsonNode y = generate(sessionA, id(yearly), "2027-28");
        assertThat(y.get("dueDate").asString()).as("default due day is the 10th, in the financial year's first month").isEqualTo("2027-04-10");

        JsonNode monthly = plan("Monthly future", "MONTHLY", Map.of("dueDay", 28));
        assertThat(generate(sessionA, id(monthly), "2027-02").get("dueDate").asString()).isEqualTo("2027-02-28");

        for (String bad : new String[] {"2027-13", "Oct 2026", "2027-Q9", "2027"}) {
            String frequency = bad.contains("Q") ? "MONTHLY" : bad.equals("2027") ? "YEARLY" : "QUARTERLY";
            JsonNode p = plan("Bad " + bad, frequency, Map.of());
            assertThat(asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(p).toString(), "period", bad)).status()).as(bad).isEqualTo(400);
        }
    }

    @Test
    void aOneTimePlanNeedsAPeriodLabelAndADueDate() {
        memberA("Asha");
        JsonNode plan = plan("Diwali collection", "ONE_TIME", Map.of());

        assertThat(asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString())).status()).as("no period").isEqualTo(400);
        assertThat(asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString(), "period", "Diwali 2026")).status()).as("no due date").isEqualTo(400);
        ApiClient.Response ok = asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString(), "period", "Diwali 2026", "dueDate", TODAY.plusDays(20).toString()));
        assertThat(ok.status()).as(ok.body()).isEqualTo(201);
        assertThat(ok.json().get("dueDate").asString()).isEqualTo(TODAY.plusDays(20).toString());
        assertThat(asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString(), "period", "Diwali 2026", "dueDate", TODAY.plusDays(20).toString())).json().get("created").asInt()).as("same label, same result").isZero();
        assertThat(asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString(), "period", "Holi 2027", "dueDate", TODAY.plusDays(90).toString())).json().get("created").asInt()).as("a new label is a new billing").isOne();
    }

    @Test
    void aDueDateInThePastMakesTheInvoiceOverdueAtOnce() {
        memberA("Asha");
        JsonNode plan = plan("Late billing", "MONTHLY", Map.of());

        generate(sessionA, id(plan), "2020-01");

        assertThat(jdbc.queryForObject("select status from invoices where fee_plan_id = ?", String.class, id(plan))).isEqualTo("OVERDUE");
    }

    @Test
    void anOverrideDueDateIsUsed() {
        memberA("Asha");
        JsonNode plan = plan("Override", "MONTHLY", Map.of("dueDay", 5));

        ApiClient.Response response = asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString(), "dueDate", TODAY.plusDays(40).toString()));

        assertThat(response.json().get("dueDate").asString()).isEqualTo(TODAY.plusDays(40).toString());
        assertThat(jdbc.queryForObject("select due_date::text from invoices where fee_plan_id = ?", String.class, id(plan))).isEqualTo(TODAY.plusDays(40).toString());
    }

    // ---- state ---------------------------------------------------------------------------------------------------------

    @Test
    void aSwitchedOffPlanCannotBeBilled() {
        memberA("Asha");
        JsonNode plan = plan("Off", "MONTHLY", Map.of());
        asA("PATCH", PLANS + "/" + id(plan), Map.of("active", false));

        ApiClient.Response response = asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString()));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("FEE_PLAN_INACTIVE");
        assertThat(invoiceNos(id(plan))).isEmpty();
    }

    @Test
    void aCancelledInvoiceCanBeBilledAgain() {
        UUID asha = memberA("Asha");
        memberA("Ravi");
        JsonNode plan = plan("Rebill", "MONTHLY", Map.of());
        generate(sessionA, id(plan), null);
        UUID ashaInvoice = UUID.fromString(jdbc.queryForObject("select id::text from invoices where fee_plan_id = ? and member_id = ?", String.class, id(plan), asha));
        assertThat(asA("POST", INVOICES + "/" + ashaInvoice + "/cancel", Map.of("reason", "Wrong amount")).status()).isEqualTo(200);

        JsonNode again = generate(sessionA, id(plan), null);

        assertThat(again.get("created").asInt()).as("only the cancelled one is billed again").isOne();
        assertThat(count("select count(*) from invoices where fee_plan_id = ? and member_id = ? and status <> 'CANCELLED'", id(plan), asha)).isOne();
        assertThat(count("select count(*) from invoices where fee_plan_id = ? and member_id = ?", id(plan), asha)).as("the cancelled one stays on record").isEqualTo(2);
    }

    @Test
    void theDatabaseRefusesADuplicateInvoiceForAPlanMemberAndPeriod() {
        UUID member = memberA("Asha");
        JsonNode plan = plan("Constraint", "MONTHLY", Map.of());
        generate(sessionA, id(plan), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "insert into invoices (id, community_id, member_id, invoice_no, kind, period, amount, due_date, status, fee_plan_id) values (gen_random_uuid(), ?, ?, 'DUP-1', 'MAINTENANCE', ?, 10, current_date, 'ISSUED', ?)",
                        communityA.getId(), member, PERIOD, id(plan)))
                .hasMessageContaining("uq_invoices_fee_plan_member_period");
    }

    // ---- emails ---------------------------------------------------------------------------------------------------------

    @Test
    void billEmailsGoOnlyToMembersWithAnAddressAndConsent() {
        String emailed = email();
        UUID m1 = member(sessionA, "Has consent", emailed, true);
        String noConsentAddress = email();
        member(sessionA, "No consent", noConsentAddress, false);
        member(sessionA, "No address", null, true);
        JsonNode plan = plan("Mail", "MONTHLY", Map.of());

        JsonNode result = generate(sessionA, id(plan), null);

        assertThat(result.get("emailsQueued").asInt()).isOne();
        assertThat(result.get("emailsSkippedNoConsent").asInt()).isOne();
        assertThat(result.get("emailsSkippedNoAddress").asInt()).isOne();
        List<Map<String, Object>> mail = outbox(emailed, "member-bill");
        assertThat(mail).hasSize(1);
        assertThat(mail.get(0).get("community_id")).isEqualTo(communityA.getId());
        String payload = mail.get(0).get("payload").toString();
        assertThat(payload).contains("Has consent").contains("1500.00").contains("INV-").contains(communityA.getName());
        assertThat(outbox(noConsentAddress, "member-bill")).isEmpty();
        assertThat(m1).isNotNull();
    }

    @Test
    void sendEmailsFalseQueuesNothing() {
        member(sessionA, "Quiet", email(), true);
        JsonNode plan = plan("Silent", "MONTHLY", Map.of());

        JsonNode result = asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString(), "sendEmails", false)).json();

        assertThat(result.get("created").asInt()).isOne();
        assertThat(result.get("emailsQueued").asInt()).isZero();
        assertThat(count("select count(*) from email_outbox where community_id = ? and template = 'member-bill'", communityA.getId())).isZero();
    }

    @Test
    void theEmailQuotaLimitsEmailsNotInvoices() {
        for (int i = 0; i < 4; i++) memberA("Member " + i);
        long alreadyQueued = count("select count(*) from email_outbox where community_id = ?", communityA.getId()); // the members' welcome emails
        Plan limited = data.customPlan("Few emails", Map.of("emails_per_month", alreadyQueued + 2), Map.of("upi_qr", true));
        jdbc.update("update communities set plan_id = ? where id = ?", limited.getId(), communityA.getId());
        JsonNode plan = plan("Quota", "MONTHLY", Map.of());

        JsonNode result = generate(sessionA, id(plan), null);

        assertThat(result.get("created").asInt()).as("every member is billed").isEqualTo(4);
        assertThat(result.get("emailsQueued").asInt()).isEqualTo(2);
        assertThat(result.get("emailsSkippedQuota").asInt()).isEqualTo(2);
    }

    @Test
    void theBillEmailCarriesAPaymentLinkWhenUpiIsSetUp() {
        String address = email();
        member(sessionA, "Payer", address, true);
        JsonNode plan = plan("With link", "MONTHLY", Map.of());
        generate(sessionA, id(plan), null);
        assertThat(outbox(address, "member-bill").get(0).get("payload").toString()).as("no UPI ID yet, so no link").contains("\"payLink\": null");

        setUpi(sessionA);
        JsonNode second = plan("With link two", "MONTHLY", Map.of());
        generate(sessionA, id(second), null);

        String payload = outbox(address, "member-bill").get(1).get("payload").toString();
        assertThat(payload).contains("/pay/");
        assertThat(count("select count(*) from payment_links where community_id = ?", communityA.getId())).isOne();
    }
}

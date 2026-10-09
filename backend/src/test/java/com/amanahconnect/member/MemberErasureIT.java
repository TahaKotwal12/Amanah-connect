package com.amanahconnect.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.dashboard.DashboardService;
import com.amanahconnect.support.AbstractDeskIT;
import com.amanahconnect.support.ApiClient;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class MemberErasureIT extends AbstractDeskIT {

    private static final String M = "/api/v1/community/members";

    @Autowired DashboardService dashboards;

    private record Subject(UUID member, String memberNo, String email, UUID paidInvoice, UUID unpaidInvoice, UUID complaint) {}

    /** A member with a paid and a part-paid invoice, a stored receipt PDF, a complaint, a registration and queued mail. */
    private Subject subject() {
        String email = email();
        UUID member = member(sessionA, "Zainab Khan", email, true);
        jdbc.update("update members set phone = '+919876500000', group_label = 'Block C' where id = ?", member);
        String memberNo = asA("GET", M + "/" + member, null).json().get("memberNo").asString();
        UUID paid = id(invoiceA(member, "800.00", TODAY.plusDays(5)));
        payOk(sessionA, paid, "800.00");
        UUID unpaid = id(invoiceA(member, "600.00", TODAY.plusDays(9)));
        payOk(sessionA, unpaid, "100.00");
        UUID receipt = UUID.fromString(jdbc.queryForObject("select id::text from receipts where community_id = ? order by created_at limit 1", String.class, communityA.getId()));
        assertThat(asA("GET", RECEIPTS + "/" + receipt + "/pdf", null).status()).isEqualTo(200); // renders and stores the PDF
        UUID complaint = id(asA("POST", "/api/v1/community/complaints", Map.of("memberId", member.toString(), "subject", "Zainab's water problem", "description", "Zainab Khan, flat C-12, no water")).json());
        UUID invite = UUID.randomUUID();
        jdbc.update("insert into member_invites (id, community_id, token_hash, expires_at, created_by) values (?, ?, ?, now() + interval '7 days', ?)",
                invite, communityA.getId(), UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), adminA.id());
        jdbc.update("insert into member_registrations (id, community_id, invite_id, member_id, full_name, email, phone, status, reviewed_by, reviewed_at) values (?, ?, ?, ?, 'Zainab Khan', ?, '+919876500000', 'APPROVED', ?, now())",
                UUID.randomUUID(), communityA.getId(), invite, member, email, adminA.id());
        return new Subject(member, memberNo, email, paid, unpaid, complaint);
    }

    private ApiClient.Response erase(UUID member, String memberNo, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberNo", memberNo);
        body.put("reason", reason);
        return asA("POST", M + "/" + member + "/anonymise", body);
    }

    /** Every figure the books are made of. */
    private Map<String, Object> books() {
        return jdbc.queryForMap("""
                select
                  (select count(*) from invoices where community_id = ?) as invoices,
                  (select coalesce(sum(amount), 0) from invoices where community_id = ?) as billed,
                  (select coalesce(sum(amount_paid), 0) from invoices where community_id = ?) as paid_on_invoices,
                  (select count(*) from payment_records where community_id = ?) as payments,
                  (select coalesce(sum(amount), 0) from payment_records where community_id = ?) as collected,
                  (select count(*) from receipts where community_id = ?) as receipts,
                  (select count(*) from ledger_entries where community_id = ?) as ledger_rows,
                  (select coalesce(sum(case when type = 'INCOME' then amount else -amount end), 0) from ledger_entries where community_id = ?) as ledger_net,
                  (select string_agg(invoice_no, ',' order by invoice_no) from invoices where community_id = ?) as invoice_numbers,
                  (select string_agg(receipt_no, ',' order by receipt_no) from receipts where community_id = ?) as receipt_numbers
                """, communityA.getId(), communityA.getId(), communityA.getId(), communityA.getId(), communityA.getId(),
                communityA.getId(), communityA.getId(), communityA.getId(), communityA.getId(), communityA.getId());
    }

    // ---- the books survive ---------------------------------------------------------------------------------------------------

    @Test
    void personalDetailsGoButEveryFinancialRecordStaysExactlyAsItWas() {
        Subject s = subject();
        dashboards.evictAll();
        Map<String, Object> before = books();
        JsonNode dashBefore = asA("GET", "/api/v1/community/dashboard", null).json();

        ApiClient.Response r = erase(s.member, s.memberNo, "Member asked for erasure by letter");

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(books()).as("invoices, payments, receipts and the ledger are untouched").isEqualTo(before);
        dashboards.evictAll();
        JsonNode dashAfter = asA("GET", "/api/v1/community/dashboard", null).json();
        assertThat(dashAfter.get("collection").toString()).isEqualTo(dashBefore.get("collection").toString());
        assertThat(dashAfter.get("overdue").toString()).isEqualTo(dashBefore.get("overdue").toString());
        assertThat(dashAfter.get("netBalance").toString()).isEqualTo(dashBefore.get("netBalance").toString());

        JsonNode result = r.json();
        assertThat(result.get("kept").get("invoices").asLong()).isEqualTo(2);
        assertThat(result.get("kept").get("payments").asLong()).isEqualTo(2);
        assertThat(result.get("kept").get("receipts").asLong()).isEqualTo(2);
        assertThat(result.get("kept").get("unpaidInvoices").asLong()).isEqualTo(1);
        assertThat(money(result.get("kept").get("outstanding"))).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(result.get("removed").get("registrations").asLong()).isEqualTo(1);
        assertThat(result.get("removed").get("complaints").asLong()).isEqualTo(1);
        assertThat(result.get("removed").get("receiptPdfs").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theMemberRowKeepsItsNumberButLosesEveryDetail() {
        Subject s = subject();

        erase(s.member, s.memberNo, "Erasure request");

        Map<String, Object> row = jdbc.queryForMap("select * from members where id = ?", s.member);
        assertThat(row.get("full_name")).isEqualTo("Erased member");
        assertThat(row.get("member_no")).isEqualTo(s.memberNo);
        assertThat(row.get("email")).isNull();
        assertThat(row.get("phone")).isNull();
        assertThat(row.get("group_label")).isNull();
        assertThat(row.get("consent_email")).isEqualTo(false);
        assertThat(row.get("status")).isEqualTo("INACTIVE");
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("anonymised_at")).isNotNull();
        assertThat(row.get("anonymised_by")).isEqualTo(adminA.id());
        assertThat(row.toString()).doesNotContain("Zainab").doesNotContain("Khan").doesNotContain(s.email).doesNotContain("9876500000").doesNotContain("Block C");
    }

    @Test
    void copiesOfTheDetailsElsewhereAreWipedToo() {
        Subject s = subject();

        erase(s.member, s.memberNo, "Erasure request");

        Map<String, Object> reg = jdbc.queryForMap("select * from member_registrations where member_id = ?", s.member);
        assertThat(reg.toString()).doesNotContain("Zainab").doesNotContain(s.email).doesNotContain("9876500000");
        assertThat(reg.get("anonymised_at")).isNotNull();
        Map<String, Object> complaint = jdbc.queryForMap("select subject, description from complaints where id = ?", s.complaint);
        assertThat(complaint.toString()).doesNotContain("Zainab").doesNotContain("C-12");
        assertThat(count("select count(*) from email_outbox where to_email = ?", s.email)).as("no mail is addressed to the old address any more").isZero();
        assertThat(jdbc.queryForList("select payload::text from email_outbox where community_id = ? and payload::text like '%Zainab%'", String.class, communityA.getId())).isEmpty();
        assertThat(jdbc.queryForList("select status from email_outbox where to_email like 'erased-%@erased.invalid' and community_id = ?", String.class, communityA.getId()))
                .as("anything still waiting to be sent was cancelled").doesNotContain("PENDING");
    }

    @Test
    void storedReceiptPdfsAreDeletedAndRenderedAnonymisedOnRequest() {
        Subject s = subject();
        List<String> keys = jdbc.queryForList("select r.pdf_key from receipts r join payment_records p on p.id = r.payment_record_id where p.member_id = ? and r.pdf_key is not null", String.class, s.member);
        assertThat(keys).isNotEmpty();
        keys.forEach(k -> assertThat(storage.has(k)).isTrue());

        erase(s.member, s.memberNo, "Erasure request");

        keys.forEach(k -> assertThat(storage.has(k)).as("stored PDF with the name on it is gone").isFalse());
        assertThat(count("select count(*) from receipts r join payment_records p on p.id = r.payment_record_id where p.member_id = ? and r.pdf_key is not null", s.member)).isZero();
        UUID receipt = UUID.fromString(jdbc.queryForObject("select r.id::text from receipts r join payment_records p on p.id = r.payment_record_id where p.member_id = ? limit 1", String.class, s.member));
        assertThat(asA("GET", RECEIPTS + "/" + receipt + "/pdf", null).status()).as("still downloadable").isEqualTo(200);
        assertThat(asA("GET", RECEIPTS + "/" + receipt, null).body()).doesNotContain("Zainab");
    }

    @Test
    void unpaidInvoicesStayOwedAndCanStillBeSettledByMemberNumber() {
        Subject s = subject();

        erase(s.member, s.memberNo, "Erasure request");

        JsonNode detail = invoiceView(sessionA, s.unpaidInvoice);
        assertThat(detail.get("invoice").get("status").asString()).isEqualTo("PARTIAL");
        assertThat(detail.toString()).doesNotContain("Zainab");
        ApiClient.Response settle = pay(sessionA, s.unpaidInvoice, "500.00", null);
        assertThat(settle.status()).as(settle.body()).isEqualTo(201);
        assertThat(invoiceView(sessionA, s.unpaidInvoice).get("invoice").get("status").asString()).isEqualTo("PAID");
    }

    @Test
    void theErasedMemberDisappearsFromListsAndSearchByNameOrEmail() {
        Subject s = subject();

        erase(s.member, s.memberNo, "Erasure request");

        assertThat(asA("GET", M + "?q=Zainab", null).body()).doesNotContain(s.member.toString());
        assertThat(asA("GET", M + "?q=" + s.email, null).body()).doesNotContain(s.member.toString());
        assertThat(asA("GET", "/api/v1/community/audit?limit=200", null).body()).doesNotContain("Zainab").doesNotContain(s.email);
    }

    // ---- the audit record ------------------------------------------------------------------------------------------------------

    @Test
    void theAuditEntryHoldsIdsNumbersAndCountsNeverTheErasedDetails() {
        Subject s = subject();

        erase(s.member, s.memberNo, "Erasure request ref 17");

        List<Map<String, Object>> rows = jdbc.queryForList("select before::text as before, after::text as after, actor_user_id, community_id from audit_logs where action = 'MEMBER_ANONYMISED' and entity_id = ?", s.member);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("actor_user_id")).isEqualTo(adminA.id());
        assertThat(row.get("community_id")).isEqualTo(communityA.getId());
        assertThat(row.get("after").toString()).contains(s.memberNo).contains("Erasure request ref 17").contains("kept");
        assertThat(row.toString()).doesNotContain("Zainab").doesNotContain(s.email).doesNotContain("9876500000");
        assertThat(asA("GET", "/api/v1/community/audit?action=MEMBER_ANONYMISED", null).json().get("items").get(0).get("summary").asString()).containsIgnoringCase("erased");
    }

    @Test
    void theEarlierTrailOfTheMemberNeverHeldTheirDetailsEither() {
        Subject s = subject();
        erase(s.member, s.memberNo, "Erasure request");

        List<String> trail = jdbc.queryForList("select coalesce(before::text, '') || coalesce(after::text, '') from audit_logs where community_id = ?", String.class, communityA.getId());

        assertThat(String.join("\n", trail)).doesNotContain("Zainab").doesNotContain(s.email);
    }

    // ---- guard rails ------------------------------------------------------------------------------------------------------------

    @Test
    void theMemberNumberMustBeTypedBackAndAReasonGiven() {
        Subject s = subject();

        assertThat(erase(s.member, "WRONG-1", "reason").status()).isEqualTo(400);
        assertThat(erase(s.member, s.memberNo, "   ").status()).isEqualTo(400);
        assertThat(asA("POST", M + "/" + s.member + "/anonymise", Map.of("reason", "x")).status()).isEqualTo(400);
        assertThat(asA("POST", M + "/" + s.member + "/anonymise", null).status()).isEqualTo(400);

        assertThat(jdbc.queryForObject("select full_name from members where id = ?", String.class, s.member)).isEqualTo("Zainab Khan");
        assertThat(count("select count(*) from audit_logs where action = 'MEMBER_ANONYMISED' and entity_id = ?", s.member)).isZero();
    }

    @Test
    void erasingTwiceIsAConflict() {
        Subject s = subject();
        assertThat(erase(s.member, s.memberNo, "first").status()).isEqualTo(200);

        ApiClient.Response again = erase(s.member, s.memberNo, "second");

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("MEMBER_ALREADY_ANONYMISED");
        assertThat(count("select count(*) from audit_logs where action = 'MEMBER_ANONYMISED' and entity_id = ?", s.member)).isEqualTo(1);
    }

    @Test
    void aMemberOfAnotherCommunityIsNotFoundAndNothingChanges() {
        UUID other = member(sessionB, "Bilal Sheikh", email(), true);
        String no = asB("GET", M + "/" + other, null).json().get("memberNo").asString();

        ApiClient.Response r = erase(other, no, "attempt");

        assertThat(r.status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select full_name from members where id = ?", String.class, other)).isEqualTo("Bilal Sheikh");
        assertThat(erase(UUID.randomUUID(), no, "attempt").status()).as("same as a missing id").isEqualTo(404);
        assertThat(asB("POST", M + "/" + other + "/anonymise", Map.of("memberNo", no, "reason", "owner may")).status()).isEqualTo(200);
    }

    @Test
    void anAlreadyDeletedMemberCanStillBeErased() {
        Subject s = subject();
        assertThat(asA("DELETE", M + "/" + s.member + "?reason=left", null).status()).isIn(200, 204, 400, 409);
        jdbc.update("update members set deleted_at = coalesce(deleted_at, now()) where id = ?", s.member);

        ApiClient.Response r = erase(s.member, s.memberNo, "Erasure request");

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select full_name from members where id = ?", String.class, s.member)).isEqualTo("Erased member");
    }

    @Test
    void anotherMemberSharingTheAddressKeepsTheirMail() {
        String shared = email();
        UUID erased = member(sessionA, "Share One", shared, true);
        UUID kept = member(sessionA, "Share Two", shared, true);
        String no = asA("GET", M + "/" + erased, null).json().get("memberNo").asString();
        long before = count("select count(*) from email_outbox where community_id = ? and to_email = ? and payload::text like '%Share Two%'", communityA.getId(), shared);
        assertThat(before).isGreaterThanOrEqualTo(1);

        assertThat(erase(erased, no, "Erasure request").status()).isEqualTo(200);

        assertThat(count("select count(*) from email_outbox where community_id = ? and to_email = ? and payload::text like '%Share Two%'", communityA.getId(), shared)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select full_name from members where id = ?", String.class, kept)).isEqualTo("Share Two");
    }

    @Test
    void onlyCommunityAdminsMayErase() {
        Subject s = subject();
        assertThat(api.call("POST", M + "/" + s.member + "/anonymise", Map.of("memberNo", s.memberNo, "reason", "x")).status()).isEqualTo(401);
        assertThat(asSuper("POST", M + "/" + s.member + "/anonymise", Map.of("memberNo", s.memberNo, "reason", "x")).status()).isIn(400, 401, 403, 404);
        assertThat(jdbc.queryForObject("select full_name from members where id = ?", String.class, s.member)).isEqualTo("Zainab Khan");
    }
}

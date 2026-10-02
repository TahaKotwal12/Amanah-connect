package com.amanahconnect.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.audit.AuditLog;
import com.amanahconnect.audit.AuditLogRepository;
import com.amanahconnect.auth.User;
import com.amanahconnect.auth.UserRole;
import com.amanahconnect.billing.Invoice;
import com.amanahconnect.billing.InvoiceRepository;
import com.amanahconnect.billing.InvoiceStatus;
import com.amanahconnect.billing.PaymentRecord;
import com.amanahconnect.billing.PaymentRecordRepository;
import com.amanahconnect.billing.Receipt;
import com.amanahconnect.billing.ReceiptRepository;
import com.amanahconnect.community.Community;
import com.amanahconnect.ledger.LedgerCategory;
import com.amanahconnect.ledger.LedgerEntry;
import com.amanahconnect.ledger.LedgerEntryRepository;
import com.amanahconnect.ledger.LedgerSource;
import com.amanahconnect.ledger.LedgerType;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.support.AbstractIntegrationTest;
import com.amanahconnect.support.TestData;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the database itself enforces the rules (uniqueness, amount checks, tenant integrity, append-only
 * audit, no hard deletes). Each test is rolled back; a violated constraint aborts the transaction, so every
 * test ends with its single violating statement.
 */
@Transactional
class ConstraintsIT extends AbstractIntegrationTest {

    @Autowired TestData data;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository members;
    @Autowired InvoiceRepository invoices;
    @Autowired PaymentRecordRepository payments;
    @Autowired ReceiptRepository receipts;
    @Autowired LedgerEntryRepository ledger;
    @Autowired AuditLogRepository audit;

    // ---- uniqueness -------------------------------------------------------------------------

    @Test
    void memberNoIsUniquePerCommunityButReusableAcrossCommunities() {
        Community a = data.community();
        Community b = data.community();
        saveMember(a, "M-001");
        assertThatCode(() -> saveMember(b, "M-001")).as("same number, other community").doesNotThrowAnyException();

        assertThatThrownBy(() -> saveMember(a, "M-001")).satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("uq_members_community_member_no");
    }

    @Test
    void invoiceNoIsUniquePerCommunityButReusableAcrossCommunities() {
        Community a = data.community();
        Community b = data.community();
        Member ma = data.member(a);
        Member mb = data.member(b);
        data.issuedInvoice(a, ma, "INV-2026-27/000001", "100.00");
        assertThatCode(() -> data.issuedInvoice(b, mb, "INV-2026-27/000001", "100.00")).doesNotThrowAnyException();

        assertThatThrownBy(() -> data.issuedInvoice(a, ma, "INV-2026-27/000001", "50.00"))
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("uq_invoices_community_invoice_no");
    }

    @Test
    void receiptNoIsUniquePerCommunity() {
        Community c = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        Member m = data.member(c);
        PaymentRecord p1 = data.payment(c, m, null, "10.00", admin);
        PaymentRecord p2 = data.payment(c, m, null, "20.00", admin);
        saveReceipt(c, p1, "RCP-2026-27/000001");

        assertThatThrownBy(() -> saveReceipt(c, p2, "RCP-2026-27/000001"))
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("uq_receipts_community_receipt_no");
    }

    // ---- amounts and statuses ---------------------------------------------------------------

    @Test
    void invoiceAmountMustBePositive() {
        Community c = data.community();
        Member m = data.member(c);

        assertThatThrownBy(() -> data.issuedInvoice(c, m, "INV-X/1", "0.00"))
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("ck_invoices_amount");
    }

    @Test
    void invoiceCannotBePaidMoreThanItsAmount() {
        Community c = data.community();
        Invoice invoice = data.issuedInvoice(c, data.member(c), "INV-X/2", "100.00");
        invoice.setAmountPaid(new BigDecimal("100.01"));

        assertThatThrownBy(() -> em.flush())
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("ck_invoices_amount_paid");
    }

    @Test
    void paidInvoiceMustBePaidInFull() {
        Community c = data.community();
        Invoice invoice = data.issuedInvoice(c, data.member(c), "INV-X/3", "100.00");
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setAmountPaid(new BigDecimal("40.00"));

        assertThatThrownBy(() -> em.flush())
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("ck_invoices_paid_in_full");
    }

    @Test
    void issuedInvoiceNeedsANumber() {
        Community c = data.community();
        Member m = data.member(c);

        assertThatThrownBy(() -> data.issuedInvoice(c, m, null, "10.00"))
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("ck_invoices_number_when_issued");
    }

    @Test
    void unknownStatusValueIsRejectedByTheDatabase() {
        Community c = data.community();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update communities set status = 'BOGUS' where id = ?", c.getId()))
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("ck_communities_status");
    }

    @Test
    void ordinaryPaymentMustBePositive() {
        Community c = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        Member m = data.member(c);

        assertThatThrownBy(() -> data.payment(c, m, null, "-5.00", admin))
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("ck_payment_records_amount_sign");
    }

    @Test
    void validReversalIsAccepted() {
        Community c = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        Member m = data.member(c);
        PaymentRecord original = data.payment(c, m, null, "100.00", admin);

        PaymentRecord reversal = reversalOf(c, m, original, "-100.00", "entered twice", admin);
        assertThatCode(() -> payments.save(reversal)).doesNotThrowAnyException();
        em.flush();
        assertThat(reversal.getAmount()).isEqualByComparingTo("-100.00");
    }

    @Test
    void positiveReversalIsRejected() {
        Community c = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        Member m = data.member(c);
        PaymentRecord original = data.payment(c, m, null, "100.00", admin);

        assertThatThrownBy(() -> {
                    payments.save(reversalOf(c, m, original, "100.00", "wrong sign", admin));
                    em.flush();
                })
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("ck_payment_records_amount_sign");
    }

    @Test
    void reversalWithoutReasonIsRejected() {
        Community c = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        Member m = data.member(c);
        PaymentRecord original = data.payment(c, m, null, "100.00", admin);

        assertThatThrownBy(() -> {
                    payments.save(reversalOf(c, m, original, "-100.00", "  ", admin));
                    em.flush();
                })
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("ck_payment_records_reversal_reason");
    }

    @Test
    void aPaymentCanOnlyBeReversedOnce() {
        Community c = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        Member m = data.member(c);
        PaymentRecord original = data.payment(c, m, null, "100.00", admin);
        payments.save(reversalOf(c, m, original, "-100.00", "first", admin));
        em.flush();

        assertThatThrownBy(() -> {
                    payments.save(reversalOf(c, m, original, "-100.00", "second", admin));
                    em.flush();
                })
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("uq_payment_records_reversed_of");
    }

    // ---- ledger -----------------------------------------------------------------------------

    @Test
    void ledgerEntryRoundTripAndReversal() {
        Community c = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        LedgerCategory repairs = data.category(c, LedgerType.EXPENSE, "Repairs");
        LedgerEntry entry = ledgerEntry(c, repairs, LedgerType.EXPENSE, "1500.50", admin);
        ledger.save(entry);
        em.flush();

        LedgerEntry reversal = ledgerEntry(c, repairs, LedgerType.EXPENSE, "-1500.50", admin);
        reversal.setReversedOf(entry);
        reversal.setReversalReason("duplicate bill");
        ledger.save(reversal);
        em.flush();

        assertThat(
                        jdbc.queryForObject(
                                "select sum(amount) from ledger_entries where community_id = ?",
                                BigDecimal.class,
                                c.getId()))
                .as("a reversal cancels the original in totals")
                .isEqualByComparingTo("0.00");
    }

    @Test
    void ledgerEntryCategoryMustHaveTheSameType() {
        Community c = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        LedgerCategory expenseCategory = data.category(c, LedgerType.EXPENSE, "Repairs");

        assertThatThrownBy(() -> {
                    ledger.save(ledgerEntry(c, expenseCategory, LedgerType.INCOME, "10.00", admin));
                    em.flush();
                })
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("fk_ledger_entries_category");
    }

    @Test
    void ledgerEntryCannotUseAnotherCommunitysCategory() {
        Community a = data.community();
        Community b = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        LedgerCategory categoryOfB = data.category(b, LedgerType.EXPENSE, "Repairs");

        assertThatThrownBy(() -> {
                    ledger.save(ledgerEntry(a, categoryOfB, LedgerType.EXPENSE, "10.00", admin));
                    em.flush();
                })
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("fk_ledger_entries_category");
    }

    @Test
    void ledgerAmountMustBePositiveUnlessReversal() {
        Community c = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        LedgerCategory repairs = data.category(c, LedgerType.EXPENSE, "Repairs");

        assertThatThrownBy(() -> {
                    ledger.save(ledgerEntry(c, repairs, LedgerType.EXPENSE, "0.00", admin));
                    em.flush();
                })
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("ck_ledger_entries_amount_sign");
    }

    // ---- tenant integrity -------------------------------------------------------------------

    @Test
    void invoiceCannotPointAtAnotherCommunitysMember() {
        Community a = data.community();
        Community b = data.community();
        Member memberOfB = data.member(b);

        assertThatThrownBy(() -> data.issuedInvoice(a, memberOfB, "INV-X/9", "10.00"))
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("fk_invoices_member");
    }

    @Test
    void paymentCannotPointAtAnotherCommunitysInvoice() {
        Community a = data.community();
        Community b = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        Member ma = data.member(a);
        Invoice invoiceOfB = data.issuedInvoice(b, data.member(b), "INV-X/10", "10.00");

        assertThatThrownBy(() -> data.payment(a, ma, invoiceOfB, "10.00", admin))
                .satisfies(ConstraintsIT::isIntegrityViolation)
                .hasMessageContaining("fk_payment_records_invoice");
    }

    // ---- never deleted / append-only --------------------------------------------------------

    @Test
    void invoicesCannotBeDeleted() {
        Community c = data.community();
        Invoice invoice = data.issuedInvoice(c, data.member(c), "INV-X/11", "10.00");

        assertThatThrownBy(() -> jdbc.update("delete from invoices where id = ?", invoice.getId()))
                .hasMessageContaining("never deleted");
    }

    @Test
    void paymentsCannotBeDeleted() {
        Community c = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        PaymentRecord payment = data.payment(c, data.member(c), null, "10.00", admin);

        assertThatThrownBy(() -> jdbc.update("delete from payment_records where id = ?", payment.getId()))
                .hasMessageContaining("never deleted");
    }

    @Test
    void receiptsCannotBeDeleted() {
        Community c = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        PaymentRecord payment = data.payment(c, data.member(c), null, "10.00", admin);
        Receipt receipt = saveReceipt(c, payment, "RCP-X/1");

        assertThatThrownBy(() -> jdbc.update("delete from receipts where id = ?", receipt.getId()))
                .hasMessageContaining("never deleted");
    }

    @Test
    void ledgerEntriesCannotBeDeleted() {
        Community c = data.community();
        UUID admin = data.user(UserRole.COMMUNITY_ADMIN).getId();
        LedgerEntry entry = ledgerEntry(c, data.category(c, LedgerType.INCOME, "Donations"), LedgerType.INCOME, "10.00", admin);
        ledger.save(entry);
        em.flush();

        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where id = ?", entry.getId()))
                .hasMessageContaining("never deleted");
    }

    @Test
    void auditLogsCanBeAppendedAndRead() {
        AuditLog saved = appendAudit();

        assertThat(
                        jdbc.queryForObject(
                                "select action from audit_logs where id = ?", String.class, saved.getId()))
                .isEqualTo("TEST_ACTION");
    }

    @Test
    void auditLogsCannotBeUpdated() {
        AuditLog saved = appendAudit();

        assertThatThrownBy(
                        () -> jdbc.update("update audit_logs set action = 'TAMPERED' where id = ?", saved.getId()))
                .hasMessageContaining("audit_logs is append-only");
    }

    @Test
    void auditLogsCannotBeDeleted() {
        AuditLog saved = appendAudit();

        assertThatThrownBy(() -> jdbc.update("delete from audit_logs where id = ?", saved.getId()))
                .hasMessageContaining("audit_logs is append-only");
    }

    @Test
    void auditLogsCannotBeTruncated() {
        appendAudit();

        assertThatThrownBy(() -> jdbc.execute("truncate table audit_logs"))
                .hasMessageContaining("audit_logs is append-only");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private Member saveMember(Community community, String memberNo) {
        Member member = new Member();
        member.setCommunityId(community.getId());
        member.setMemberNo(memberNo);
        member.setFullName("Someone");
        members.save(member);
        em.flush();
        return member;
    }

    private Receipt saveReceipt(Community community, PaymentRecord payment, String receiptNo) {
        Receipt receipt = new Receipt();
        receipt.setCommunityId(community.getId());
        receipt.setPaymentRecord(payment);
        receipt.setReceiptNo(receiptNo);
        receipts.save(receipt);
        em.flush();
        return receipt;
    }

    private PaymentRecord reversalOf(Community c, Member m, PaymentRecord original, String amount, String reason, UUID admin) {
        PaymentRecord reversal = new PaymentRecord();
        reversal.setCommunityId(c.getId());
        reversal.setMember(m);
        reversal.setAmount(new BigDecimal(amount));
        reversal.setMethod(original.getMethod());
        reversal.setReceivedOn(LocalDate.now());
        reversal.setRecordedBy(admin);
        reversal.setReversedOf(original);
        reversal.setReversalReason(reason);
        return reversal;
    }

    private LedgerEntry ledgerEntry(Community c, LedgerCategory category, LedgerType type, String amount, UUID admin) {
        LedgerEntry entry = new LedgerEntry();
        entry.setCommunityId(c.getId());
        entry.setType(type);
        entry.setCategory(category);
        entry.setAmount(new BigDecimal(amount));
        entry.setEntryDate(LocalDate.now());
        entry.setTitle("Test entry");
        entry.setSource(LedgerSource.MANUAL);
        entry.setCreatedBy(admin);
        return entry;
    }

    private AuditLog appendAudit() {
        User actor = data.user(UserRole.SUPER_ADMIN);
        AuditLog log = new AuditLog();
        log.setActorUserId(actor.getId());
        log.setAction("TEST_ACTION");
        log.setEntityType("Test");
        log.setAfter(Map.of("k", "v"));
        audit.save(log);
        em.flush();
        return log;
    }

    /** A violated constraint, as raised by PostgreSQL (SQLState class 23 = integrity constraint violation). */
    private static void isIntegrityViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23")) {
                return;
            }
        }
        throw new AssertionError("Expected a PostgreSQL integrity violation (SQLState 23xxx) but got: " + error);
    }
}

package com.amanahconnect.billing;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.billing.BillingDtos.ReceiptView;
import com.amanahconnect.common.FinancialYear;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.plan.PlanLimitService;
import com.amanahconnect.tenant.TenantGuard;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Receipts: numbered from the gap-free sequence of the financial year the money was received in; never deleted. */
@Service
@Transactional
public class ReceiptService {

    private final ReceiptRepository receipts;
    private final PaymentRecordRepository payments;
    private final CommunityRepository communities;
    private final NumberingService numbering;
    private final BillingEmails emails;
    private final PlanLimitService planLimits;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final Clock clock;

    public ReceiptService(
            ReceiptRepository receipts,
            PaymentRecordRepository payments,
            CommunityRepository communities,
            NumberingService numbering,
            BillingEmails emails,
            PlanLimitService planLimits,
            NamedParameterJdbcTemplate jdbc,
            AuditService audit,
            TenantGuard tenantGuard,
            Clock clock) {
        this.receipts = receipts;
        this.payments = payments;
        this.communities = communities;
        this.numbering = numbering;
        this.emails = emails;
        this.planLimits = planLimits;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.clock = clock;
    }

    /** Numbers and creates the receipt of a payment, inside the payment's transaction (a failed payment gives the number back). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt issue(Community community, PaymentRecord payment) {
        String fy = FinancialYear.labelFor(payment.getReceivedOn(), community.getFinancialYearStartMonth());
        Receipt receipt = new Receipt();
        receipt.setCommunityId(community.getId());
        receipt.setReceiptNo(numbering.next(community.getId(), CounterType.RECEIPT, fy));
        receipt.setPaymentRecord(payment);
        receipts.save(receipt);
        payment.setReceipt(receipt);
        return receipt;
    }

    @Transactional(readOnly = true)
    public ReceiptView get(UUID communityId, UUID id) {
        return view(communityId, tenantGuard.found(receipts.findByIdAndCommunityId(id, communityId)));
    }

    @Transactional(readOnly = true)
    public PageResponse<ReceiptView> list(UUID communityId, LocalDate from, LocalDate to, String q, Boolean reversed, Pageable page) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", communityId);
        StringBuilder where = new StringBuilder(" WHERE r.community_id = :c");
        if (from != null) { where.append(" AND p.received_on >= :from"); params.addValue("from", from); }
        if (to != null) { where.append(" AND p.received_on <= :to"); params.addValue("to", to); }
        if (reversed != null) {
            where.append(reversed ? " AND" : " AND NOT").append(" EXISTS (SELECT 1 FROM payment_records rv WHERE rv.community_id = r.community_id AND rv.reversed_of = p.id)");
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (lower(r.receipt_no) LIKE :q ESCAPE '\\' OR lower(coalesce(m.full_name, p.donor_name)) LIKE :q ESCAPE '\\' OR lower(coalesce(i.invoice_no, '')) LIKE :q ESCAPE '\\')");
            params.addValue("q", "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        String from_ = " FROM receipts r JOIN payment_records p ON p.id = r.payment_record_id AND p.community_id = r.community_id"
                + " LEFT JOIN members m ON m.id = p.member_id AND m.community_id = p.community_id LEFT JOIN invoices i ON i.id = p.invoice_id AND i.community_id = p.community_id";
        Long total = jdbc.queryForObject("SELECT count(*)" + from_ + where, params, Long.class);
        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        String order = page.getSort().stream().findFirst().map(o -> {
            String col = switch (o.getProperty()) { case "receiptNo" -> "r.receipt_no"; case "amount" -> "p.amount"; case "receivedOn" -> "p.received_on"; default -> "r.created_at"; };
            return col + (o.isAscending() ? " ASC" : " DESC");
        }).orElse("r.created_at DESC");
        var items = jdbc.query(
                "SELECT r.id, r.receipt_no, p.id AS payment_id, i.id AS invoice_id, i.invoice_no, coalesce(m.full_name, p.donor_name) AS payer, p.amount, p.method, p.reference, p.received_on,"
                        + " rv.received_on AS reversed_on, rv.reversal_reason, r.emailed_at, r.created_at" + from_
                        + " LEFT JOIN payment_records rv ON rv.community_id = p.community_id AND rv.reversed_of = p.id" + where + " ORDER BY " + order + ", r.id LIMIT :limit OFFSET :offset",
                params, (rs, row) -> new ReceiptView(
                        rs.getObject("id", UUID.class), rs.getString("receipt_no"), rs.getObject("payment_id", UUID.class), rs.getObject("invoice_id", UUID.class), rs.getString("invoice_no"),
                        rs.getString("payer"), Money.of(rs.getBigDecimal("amount")), PaymentMethod.valueOf(rs.getString("method")), rs.getString("reference"),
                        rs.getObject("received_on", LocalDate.class), rs.getObject("reversed_on") != null, rs.getObject("reversed_on", LocalDate.class), rs.getString("reversal_reason"),
                        rs.getTimestamp("emailed_at") == null ? null : rs.getTimestamp("emailed_at").toInstant(), rs.getTimestamp("created_at").toInstant()));
        return new PageResponse<>(items, total == null ? 0 : total, page.getPageNumber(), page.getPageSize());
    }

    /** Sends the receipt email again (a deliberate action: it needs an address, not consent). A reversed receipt is not re-sent. */
    public void resend(UUID communityId, UUID id) {
        Receipt receipt = tenantGuard.found(receipts.findByIdAndCommunityId(id, communityId));
        PaymentRecord payment = receipt.getPaymentRecord();
        if (payments.existsByCommunityIdAndReversedOfId(communityId, payment.getId())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This receipt was reversed and is not sent again.");
        }
        if (payment.getMember() == null || payment.getMember().getEmail() == null) {
            throw new ApiException(ErrorCode.MEMBER_NOT_EMAILABLE, "There is no email address to send this receipt to.");
        }
        planLimits.checkEmailQuota(communityId);
        Community community = communities.findById(communityId).orElseThrow(NotFoundException::new);
        var session = emails.session(community);
        java.math.BigDecimal balance = payment.getInvoice() == null ? null : InvoiceStatusRules.balance(payment.getInvoice().getAmount(), payment.getInvoice().getAmountPaid()).amount();
        BillingEmails.Outcome outcome = session.receipt(receipt, payment, balance, false);
        if (outcome != BillingEmails.Outcome.QUEUED) {
            throw new ApiException(ErrorCode.MEMBER_NOT_EMAILABLE, "The receipt could not be queued (" + outcome + ").");
        }
        receipt.setEmailedAt(clock.instant());
        receipts.save(receipt);
        audit.record("RECEIPT_RESENT", "Receipt", id, null, Map.of("receiptNo", receipt.getReceiptNo()));
    }

    private ReceiptView view(UUID communityId, Receipt r) {
        PaymentRecord p = r.getPaymentRecord();
        var reversal = payments.findByCommunityIdAndReversedOfId(communityId, p.getId());
        return new ReceiptView(
                r.getId(), r.getReceiptNo(), p.getId(), p.getInvoice() == null ? null : p.getInvoice().getId(), p.getInvoice() == null ? null : p.getInvoice().getInvoiceNo(),
                p.getMember() == null ? p.getDonorName() : p.getMember().getFullName(), Money.of(p.getAmount()), p.getMethod(), p.getReference(), p.getReceivedOn(),
                reversal.isPresent(), reversal.map(PaymentRecord::getReceivedOn).orElse(null), reversal.map(PaymentRecord::getReversalReason).orElse(null), r.getEmailedAt(), r.getCreatedAt());
    }
}

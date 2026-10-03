package com.amanahconnect.billing;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.billing.BillingDtos.CancelInvoiceRequest;
import com.amanahconnect.billing.BillingDtos.CreateInvoiceRequest;
import com.amanahconnect.billing.BillingDtos.InvoiceDetail;
import com.amanahconnect.billing.BillingDtos.InvoiceView;
import com.amanahconnect.billing.BillingDtos.PayLinkView;
import com.amanahconnect.billing.BillingDtos.PaymentView;
import com.amanahconnect.billing.BillingDtos.UpdateInvoiceRequest;
import com.amanahconnect.billing.BillingDtos.UpiPayment;
import com.amanahconnect.billing.InvoiceQueries.InvoiceFilter;
import com.amanahconnect.common.FinancialYear;
import com.amanahconnect.common.Text;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.plan.PlanLimitService;
import com.amanahconnect.tenant.TenantGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoices of one community. Amounts paid and statuses are recomputed from the payment rows on the server, never taken from
 * a client. An invoice is never deleted: a mistake is cancelled with a reason (after any payments are reversed).
 */
@Service
@Transactional
public class InvoiceService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final InvoiceRepository invoices;
    private final MemberRepository members;
    private final PaymentRecordRepository payments;
    private final CommunityRepository communities;
    private final NumberingService numbering;
    private final BillingEmails emails;
    private final PayLinkService payLinks;
    private final UpiPayments upi;
    private final InvoiceQueries queries;
    private final PlanLimitService planLimits;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final Clock clock;
    private final jakarta.persistence.EntityManager em;

    public InvoiceService(
            InvoiceRepository invoices,
            MemberRepository members,
            PaymentRecordRepository payments,
            CommunityRepository communities,
            NumberingService numbering,
            BillingEmails emails,
            PayLinkService payLinks,
            UpiPayments upi,
            InvoiceQueries queries,
            PlanLimitService planLimits,
            AuditService audit,
            TenantGuard tenantGuard,
            Clock clock,
            jakarta.persistence.EntityManager em) {
        this.invoices = invoices;
        this.members = members;
        this.payments = payments;
        this.communities = communities;
        this.numbering = numbering;
        this.emails = emails;
        this.payLinks = payLinks;
        this.upi = upi;
        this.queries = queries;
        this.planLimits = planLimits;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.clock = clock;
        this.em = em;
    }

    // ---- reads ---------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<InvoiceView> list(UUID communityId, InvoiceFilter filter, Pageable page) {
        return queries.search(communityId, filter, page);
    }

    @Transactional(readOnly = true)
    public InvoiceDetail get(UUID communityId, UUID id) {
        Invoice invoice = find(communityId, id);
        List<PaymentRecord> rows = payments.findByCommunityIdAndInvoiceId(communityId, id);
        Set<UUID> reversed = new HashSet<>();
        rows.forEach(p -> { if (p.getReversedOf() != null) reversed.add(p.getReversedOf().getId()); });
        List<PaymentView> views = rows.stream()
                .sorted(java.util.Comparator.comparing(PaymentRecord::getCreatedAt).thenComparing(PaymentRecord::getId))
                .map(p -> Views.payment(p, reversed)).toList();
        return new InvoiceDetail(Views.invoice(invoice), views);
    }

    // ---- manual invoices ----------------------------------------------------------------------------------------------

    public InvoiceView create(UUID communityId, CreateInvoiceRequest request) {
        Member member = tenantGuard.found(members.findByIdAndCommunityIdAndDeletedAtIsNull(request.memberId(), communityId));
        rejectLegacyKind(request.kind());
        LocalDate today = today();
        checkDueDate(request.dueDate(), today);
        Invoice invoice = new Invoice();
        invoice.setCommunityId(communityId);
        invoice.setMember(member);
        invoice.setKind(request.kind());
        invoice.setDescription(Text.singleLine(request.description()));
        invoice.setPeriod(Text.blankToNull(Text.singleLine(request.period())));
        invoice.setAmount(Money.of(request.amount()).amount());
        invoice.setDueDate(request.dueDate());
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setIssuedOn(today);
        boolean draft = Boolean.TRUE.equals(request.draft());
        Community community = community(communityId);
        if (!draft) {
            assignNumberAndOpen(community, invoice, today);
        }
        invoices.save(invoice);
        audit.record("INVOICE_CREATED", "Invoice", invoice.getId(), null, snapshot(invoice));
        if (!draft && !Boolean.FALSE.equals(request.sendEmail())) {
            emailBill(community, invoice, emails.session(community), true);
        }
        return Views.invoice(invoice);
    }

    public InvoiceView updateDraft(UUID communityId, UUID id, UpdateInvoiceRequest request) {
        Invoice invoice = lock(communityId, id);
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Only a draft can be edited. An issued invoice is cancelled and re-issued instead.");
        }
        Map<String, Object> before = snapshot(invoice);
        if (request.kind() != null) {
            rejectLegacyKind(request.kind());
            invoice.setKind(request.kind());
        }
        if (request.description() != null) invoice.setDescription(Text.singleLine(request.description()));
        if (request.amount() != null) invoice.setAmount(Money.of(request.amount()).amount());
        if (request.dueDate() != null) {
            checkDueDate(request.dueDate(), today());
            invoice.setDueDate(request.dueDate());
        }
        if (request.period() != null) invoice.setPeriod(Text.blankToNull(Text.singleLine(request.period())));
        invoices.save(invoice);
        audit.record("INVOICE_UPDATED", "Invoice", id, before, snapshot(invoice));
        return Views.invoice(invoice);
    }

    /** Gives a draft its number and makes it payable. */
    public InvoiceView issue(UUID communityId, UUID id, Boolean sendEmail) {
        Invoice invoice = lock(communityId, id);
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This invoice is already " + invoice.getStatus().name().toLowerCase() + ".");
        }
        Community community = community(communityId);
        Map<String, Object> before = snapshot(invoice);
        assignNumberAndOpen(community, invoice, today());
        invoices.save(invoice);
        audit.record("INVOICE_ISSUED", "Invoice", id, before, snapshot(invoice));
        if (!Boolean.FALSE.equals(sendEmail)) {
            emailBill(community, invoice, emails.session(community), true);
        }
        return Views.invoice(invoice);
    }

    /**
     * Cancels an invoice that nothing has been paid on (reverse the payments first). The number stays used: numbers are
     * gap-free, so a cancelled invoice keeps its place in the sequence. Its payment links stop working.
     */
    public InvoiceView cancel(UUID communityId, UUID id, CancelInvoiceRequest request) {
        Invoice invoice = lock(communityId, id);
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This invoice is already cancelled.");
        }
        BigDecimal paid = payments.sumAmountByCommunityIdAndInvoiceId(communityId, id);
        if (paid.signum() != 0) {
            throw new ApiException(ErrorCode.INVOICE_NOT_PAYABLE,
                    "%s has been paid on. Reverse its payments first, then cancel it.".formatted(invoice.getInvoiceNo() == null ? "This invoice" : invoice.getInvoiceNo()));
        }
        Map<String, Object> before = snapshot(invoice);
        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoice.setCancelReason(request.reason().trim());
        invoice.setCancelledAt(clock.instant());
        invoice.setCancelledBy(AuditService.currentActorId());
        invoices.save(invoice);
        payLinks.revokeAll(communityId, id);
        Map<String, Object> after = snapshot(invoice);
        after.put("reason", invoice.getCancelReason());
        audit.record("INVOICE_CANCELLED", "Invoice", id, before, after);
        return Views.invoice(invoice);
    }

    // ---- sharing the bill -----------------------------------------------------------------------------------------------

    /** Sends the bill email again, with a fresh payment link. A deliberate action, so it needs an address but not consent. */
    public void resendBill(UUID communityId, UUID id) {
        Invoice invoice = find(communityId, id);
        requireOpen(invoice);
        if (invoice.getMember().getEmail() == null) {
            throw new ApiException(ErrorCode.MEMBER_NOT_EMAILABLE, "This member has no email address.");
        }
        planLimits.checkEmailQuota(communityId);
        Community community = community(communityId);
        BillingEmails.Outcome outcome = emailBill(community, invoice, emails.session(community), false);
        if (outcome != BillingEmails.Outcome.QUEUED) {
            throw new ApiException(ErrorCode.MEMBER_NOT_EMAILABLE, "The bill could not be queued (" + outcome + ").");
        }
        audit.record("INVOICE_BILL_RESENT", "Invoice", id, null, Map.of("invoiceNo", String.valueOf(invoice.getInvoiceNo())));
    }

    /** A payment-page link for the admin to share by any channel (WhatsApp, SMS, print). */
    public PayLinkView createPayLink(UUID communityId, UUID id) {
        Invoice invoice = find(communityId, id);
        requireOpen(invoice);
        Community community = community(communityId);
        upi.require(community, invoice); // there is nothing to show without a UPI ID, so say so now
        PayLinkView link = payLinks.create(communityId, id, AuditService.currentActorId());
        audit.record("PAY_LINK_CREATED", "Invoice", id, null, Map.of("expiresAt", link.expiresAt().toString()));
        return link;
    }

    @Transactional(readOnly = true)
    public byte[] upiQr(UUID communityId, UUID id) {
        Invoice invoice = find(communityId, id);
        UpiPayment payment = upi.require(community(communityId), invoice);
        return upi.qrPng(payment.link());
    }

    // ---- used by generation and payments ---------------------------------------------------------------------------------

    /** Numbers the invoice from the community's gap-free sequence for the financial year of today and opens it for payment. */
    public void assignNumberAndOpen(Community community, Invoice invoice, LocalDate today) {
        String fy = FinancialYear.labelFor(today, community.getFinancialYearStartMonth());
        invoice.setInvoiceNo(numbering.next(community.getId(), CounterType.INVOICE, fy));
        invoice.setIssuedOn(today);
        invoice.setStatus(InvoiceStatusRules.derive(invoice.getAmount(), BigDecimal.ZERO, invoice.getDueDate(), today));
    }

    /** Queues the bill (with a payment link when UPI is set up). Returns what happened to the email. */
    public BillingEmails.Outcome emailBill(Community community, Invoice invoice, BillingEmails.Session session, boolean automatic) {
        PayLinkView link = null;
        if (upi.tryBuild(community, invoice).isPresent()) {
            em.flush(); // the link row references the invoice row
            link = payLinks.create(community.getId(), invoice.getId(), AuditService.currentActorId());
        }
        return session.bill(invoice, link, automatic);
    }

    /** Queues a payment reminder or overdue notice (with a fresh payment link when UPI is set up). */
    public BillingEmails.Outcome emailReminder(Community community, Invoice invoice, BillingEmails.Session session, com.amanahconnect.reminder.ReminderKind kind, long daysOverdue) {
        PayLinkView link = null;
        if (upi.tryBuild(community, invoice).isPresent()) {
            link = payLinks.create(community.getId(), invoice.getId(), null);
        }
        return session.reminder(invoice, kind, link, daysOverdue);
    }

    public Invoice find(UUID communityId, UUID id) {
        return tenantGuard.found(invoices.findByIdAndCommunityId(id, communityId));
    }

    public Invoice lock(UUID communityId, UUID id) {
        return tenantGuard.found(invoices.findWithLockByIdAndCommunityId(id, communityId));
    }

    public Community community(UUID communityId) {
        return communities.findById(communityId).orElseThrow(NotFoundException::new);
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    static Map<String, Object> snapshot(Invoice i) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("invoiceNo", i.getInvoiceNo());
        map.put("status", i.getStatus() == null ? null : i.getStatus().name());
        map.put("kind", i.getKind() == null ? null : i.getKind().name());
        map.put("memberId", i.getMember() == null ? null : i.getMember().getId().toString());
        map.put("period", i.getPeriod());
        map.put("amount", i.getAmount() == null ? null : i.getAmount().toPlainString());
        map.put("amountPaid", i.getAmountPaid() == null ? null : i.getAmountPaid().toPlainString());
        map.put("dueDate", i.getDueDate() == null ? null : i.getDueDate().toString());
        return map;
    }

    private static void requireOpen(Invoice invoice) {
        if (!InvoiceStatusRules.acceptsPayments(invoice.getStatus())) {
            throw new ApiException(ErrorCode.INVOICE_NOT_PAYABLE, "This invoice is " + invoice.getStatus().name().toLowerCase() + " and has nothing to pay.");
        }
    }

    private static void rejectLegacyKind(FeeKind kind) {
        if (kind == FeeKind.MEMBERSHIP) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.",
                    List.of("kind: MEMBERSHIP is only used for imported invoices; choose MAINTENANCE, SUBSCRIPTION, DONATION, EVENT, FINE or OTHER"));
        }
    }

    private static void checkDueDate(LocalDate due, LocalDate today) {
        if (due.isAfter(today.plusYears(5)) || due.isBefore(today.minusYears(10))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("dueDate: must be within 10 years back and 5 years ahead"));
        }
    }
}

package com.amanahconnect.billing;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.billing.BillingDtos.InvoiceView;
import com.amanahconnect.billing.BillingDtos.PaymentResult;
import com.amanahconnect.billing.BillingDtos.PaymentView;
import com.amanahconnect.billing.BillingDtos.RecordDonationRequest;
import com.amanahconnect.billing.BillingDtos.RecordPaymentRequest;
import com.amanahconnect.billing.BillingDtos.ReversePaymentRequest;
import com.amanahconnect.common.Text;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.community.Community;
import com.amanahconnect.ledger.LedgerPostings;
import com.amanahconnect.ledger.SystemCategories;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.tenant.TenantGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Recording money. One database transaction does all of it or none of it: the payment row, its receipt (numbered from the
 * gap-free sequence), the invoice's new total and status, and the linked INCOME ledger entry.
 *
 * <p>Two admins cannot double-record: the invoice row is locked for the whole transaction, so the second request sees
 * the first one's payment, and an overpayment is refused. A retried request with the same {@code Idempotency-Key} gets the
 * original result. The invoice's {@code amount_paid} is always recomputed from the payment rows, never trusted.
 *
 * <p>Nothing is ever deleted or edited away: a mistake is reversed by an offsetting negative payment row and ledger entry.
 */
@Service
@Transactional
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final InvoiceService invoiceService;
    private final InvoiceRepository invoices;
    private final PaymentRecordRepository payments;
    private final MemberRepository members;
    private final ReceiptService receiptService;
    private final ReceiptPdfService pdfService;
    private final LedgerPostings ledger;
    private final PaymentGateways gateways;
    private final IdempotencyGuard idempotency;
    private final BillingEmails emails;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final jakarta.persistence.EntityManager em;
    private final Clock clock;

    public PaymentService(
            InvoiceService invoiceService,
            InvoiceRepository invoices,
            PaymentRecordRepository payments,
            MemberRepository members,
            ReceiptService receiptService,
            ReceiptPdfService pdfService,
            LedgerPostings ledger,
            PaymentGateways gateways,
            IdempotencyGuard idempotency,
            BillingEmails emails,
            AuditService audit,
            TenantGuard tenantGuard,
            jakarta.persistence.EntityManager em,
            Clock clock) {
        this.invoiceService = invoiceService;
        this.invoices = invoices;
        this.payments = payments;
        this.members = members;
        this.receiptService = receiptService;
        this.pdfService = pdfService;
        this.ledger = ledger;
        this.gateways = gateways;
        this.idempotency = idempotency;
        this.emails = emails;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.em = em;
        this.clock = clock;
    }

    // ---- a payment against an invoice -----------------------------------------------------------------------------------

    public PaymentResult record(UUID communityId, UUID invoiceId, RecordPaymentRequest request, String idempotencyKey) {
        String key = IdempotencyGuard.validate(idempotencyKey);
        LocalDate today = invoiceService.today();
        LocalDate receivedOn = request.receivedOn() == null ? today : request.receivedOn();
        checkDate("receivedOn", receivedOn, today);
        Money amount = Money.of(request.amount());
        String reference = Text.blankToNull(Text.singleLine(request.reference()));
        String hash = IdempotencyGuard.hash("PAYMENT", invoiceId, amount, request.method(), reference, request.receivedOn());

        Optional<PaymentRecord> replay = idempotency.begin(communityId, key, hash);
        if (replay.isPresent()) {
            return replayed(replay.get());
        }
        Invoice invoice = invoiceService.lock(communityId, invoiceId);
        if (request.expectedVersion() != null && request.expectedVersion() != invoice.getVersion()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "The invoice changed since you looked at it (another payment, a reversal or a cancellation). Reload and check again.");
        }
        if (!InvoiceStatusRules.acceptsPayments(invoice.getStatus())) {
            throw new ApiException(ErrorCode.INVOICE_NOT_PAYABLE, "This invoice is " + invoice.getStatus().name().toLowerCase() + " and takes no payments.");
        }
        BigDecimal paidBefore = payments.sumAmountByCommunityIdAndInvoiceId(communityId, invoiceId);
        Money balance = Money.of(invoice.getAmount()).minus(Money.of(paidBefore));
        if (amount.compareTo(balance) > 0) {
            throw new ApiException(ErrorCode.OVERPAYMENT,
                    "This payment is %s but only %s is due on %s.".formatted(amount, balance, invoice.getInvoiceNo()), List.of(),
                    Map.of("balance", balance.toString(), "amount", amount.toString()));
        }
        Community community = invoiceService.community(communityId);
        confirm(communityId, invoiceId, amount, request.method(), reference);

        PaymentRecord payment = new PaymentRecord();
        payment.setCommunityId(communityId);
        payment.setInvoice(invoice);
        payment.setMember(invoice.getMember());
        payment.setAmount(amount.amount());
        payment.setMethod(request.method());
        payment.setReference(reference);
        payment.setReceivedOn(receivedOn);
        payment.setRecordedBy(AuditService.currentActorId());
        payment.setIdempotencyKey(key);
        payment.setRequestHash(key == null ? null : hash);
        payments.save(payment);
        em.flush(); // the receipt row references the payment row (and the payment points back at it afterwards)
        Receipt receipt = receiptService.issue(community, payment);
        ledger.postPayment(communityId, SystemCategories.forKind(invoice.getKind()), amount.amount(), receivedOn,
                "Payment " + receipt.getReceiptNo() + " for invoice " + invoice.getInvoiceNo(), payment.getId(), AuditService.currentActorId());

        BigDecimal paidNow = paidBefore.add(amount.amount());
        invoice.setAmountPaid(paidNow);
        invoice.setStatus(InvoiceStatusRules.derive(invoice.getAmount(), paidNow, invoice.getDueDate(), today));
        invoices.save(invoice);
        em.flush();

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("invoiceNo", invoice.getInvoiceNo());
        after.put("amount", amount.toString());
        after.put("method", request.method().name());
        after.put("receiptNo", receipt.getReceiptNo());
        after.put("invoiceStatus", invoice.getStatus().name());
        audit.record("PAYMENT_RECORDED", "PaymentRecord", payment.getId(), null, after);

        queueReceiptEmail(community, receipt, payment, InvoiceStatusRules.balance(invoice.getAmount(), paidNow).amount());
        afterCommit(() -> pdfService.generateAndStore(communityId, receipt.getId()));
        return new PaymentResult(Views.payment(payment, Set.of()), Views.invoice(invoice), false);
    }

    // ---- reversal ---------------------------------------------------------------------------------------------------------

    /**
     * Reverses a payment in full: a new row with the negative amount, a negative ledger entry, the invoice's total and
     * status recomputed. The original payment and its receipt stay (the receipt is marked reversed).
     */
    public PaymentResult reverse(UUID communityId, UUID paymentId, ReversePaymentRequest request, String idempotencyKey) {
        String key = IdempotencyGuard.validate(idempotencyKey);
        LocalDate today = invoiceService.today();
        String reason = request.reason().trim();
        String hash = IdempotencyGuard.hash("REVERSAL", paymentId, reason, request.reversedOn());

        Optional<PaymentRecord> replay = idempotency.begin(communityId, key, hash);
        if (replay.isPresent()) {
            return replayed(replay.get());
        }
        PaymentRecord original = tenantGuard.found(payments.findWithLockByIdAndCommunityId(paymentId, communityId));
        Invoice invoice = original.getInvoice() == null ? null : invoiceService.lock(communityId, original.getInvoice().getId());
        if (original.getReversedOf() != null) {
            throw new ApiException(ErrorCode.ALREADY_REVERSED, "This is itself a reversal and cannot be reversed.");
        }
        if (payments.existsByCommunityIdAndReversedOfId(communityId, paymentId)) {
            throw new ApiException(ErrorCode.ALREADY_REVERSED, "This payment was already reversed.");
        }
        LocalDate reversedOn = request.reversedOn() == null ? today : request.reversedOn();
        checkDate("reversedOn", reversedOn, today);
        if (reversedOn.isBefore(original.getReceivedOn())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("reversedOn: cannot be before the payment was received (" + original.getReceivedOn() + ")"));
        }
        Community community = invoiceService.community(communityId);

        PaymentRecord reversal = new PaymentRecord();
        reversal.setCommunityId(communityId);
        reversal.setInvoice(original.getInvoice());
        reversal.setMember(original.getMember());
        reversal.setDonorName(original.getDonorName());
        reversal.setAmount(original.getAmount().negate());
        reversal.setMethod(original.getMethod());
        reversal.setReference(original.getReference());
        reversal.setReceivedOn(reversedOn);
        reversal.setRecordedBy(AuditService.currentActorId());
        reversal.setReversedOf(original);
        reversal.setReversalReason(reason);
        reversal.setIdempotencyKey(key);
        reversal.setRequestHash(key == null ? null : hash);
        payments.save(reversal);
        ledger.postReversal(communityId, paymentId, reversal.getId(), reversedOn, reason, AuditService.currentActorId());

        InvoiceView invoiceView = null;
        if (invoice != null) {
            em.flush();
            BigDecimal paid = payments.sumAmountByCommunityIdAndInvoiceId(communityId, invoice.getId());
            invoice.setAmountPaid(paid);
            invoice.setStatus(InvoiceStatusRules.derive(invoice.getAmount(), paid, invoice.getDueDate(), today));
            invoices.save(invoice);
            invoiceView = Views.invoice(invoice);
        }
        em.flush();

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reversalOf", paymentId.toString());
        after.put("amount", Money.of(reversal.getAmount()).toString());
        after.put("reason", reason);
        after.put("invoiceStatus", invoice == null ? null : invoice.getStatus().name());
        audit.record("PAYMENT_REVERSED", "PaymentRecord", reversal.getId(), Map.of("paymentId", paymentId.toString()), after);

        Receipt receipt = original.getReceipt();
        if (receipt != null) {
            afterCommit(() -> pdfService.generateAndStore(communityId, receipt.getId())); // re-render with the REVERSED banner
        }
        return new PaymentResult(Views.payment(reversal, Set.of()), invoiceView, false);
    }

    // ---- donations ---------------------------------------------------------------------------------------------------------

    /** A donation, from a member or an anonymous donor, that is not tied to an invoice. It gets a receipt and a ledger entry. */
    public PaymentResult donate(UUID communityId, RecordDonationRequest request, String idempotencyKey) {
        String key = IdempotencyGuard.validate(idempotencyKey);
        String donor = Text.blankToNull(Text.singleLine(request.donorName()));
        if ((request.memberId() == null) == (donor == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("memberId or donorName: give exactly one"));
        }
        LocalDate today = invoiceService.today();
        LocalDate receivedOn = request.receivedOn() == null ? today : request.receivedOn();
        checkDate("receivedOn", receivedOn, today);
        Money amount = Money.of(request.amount());
        String reference = Text.blankToNull(Text.singleLine(request.reference()));
        String hash = IdempotencyGuard.hash("DONATION", request.memberId(), donor, amount, request.method(), reference, request.receivedOn());

        Optional<PaymentRecord> replay = idempotency.begin(communityId, key, hash);
        if (replay.isPresent()) {
            return replayed(replay.get());
        }
        Member member = request.memberId() == null ? null : tenantGuard.found(members.findByIdAndCommunityIdAndDeletedAtIsNull(request.memberId(), communityId));
        Community community = invoiceService.community(communityId);
        confirm(communityId, null, amount, request.method(), reference);

        PaymentRecord payment = new PaymentRecord();
        payment.setCommunityId(communityId);
        payment.setMember(member);
        payment.setDonorName(member == null ? donor : null);
        payment.setAmount(amount.amount());
        payment.setMethod(request.method());
        payment.setReference(reference);
        payment.setReceivedOn(receivedOn);
        payment.setRecordedBy(AuditService.currentActorId());
        payment.setIdempotencyKey(key);
        payment.setRequestHash(key == null ? null : hash);
        payments.save(payment);
        em.flush();
        Receipt receipt = receiptService.issue(community, payment);
        ledger.postPayment(communityId, SystemCategories.DONATIONS, amount.amount(), receivedOn, "Donation " + receipt.getReceiptNo(), payment.getId(), AuditService.currentActorId());
        em.flush();

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("amount", amount.toString());
        after.put("method", request.method().name());
        after.put("receiptNo", receipt.getReceiptNo());
        after.put("memberId", member == null ? null : member.getId().toString());
        after.put("anonymous", member == null);
        audit.record("DONATION_RECORDED", "PaymentRecord", payment.getId(), null, after);

        if (member != null) {
            queueReceiptEmail(community, receipt, payment, null);
        }
        afterCommit(() -> pdfService.generateAndStore(communityId, receipt.getId()));
        return new PaymentResult(Views.payment(payment, Set.of()), null, false);
    }

    // ---- reads --------------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PaymentView get(UUID communityId, UUID id) {
        PaymentRecord payment = tenantGuard.found(payments.findByIdAndCommunityId(id, communityId));
        boolean reversed = payments.existsByCommunityIdAndReversedOfId(communityId, id);
        return Views.payment(payment, reversed ? Set.of(id) : Set.of());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private PaymentResult replayed(PaymentRecord payment) {
        boolean reversed = payments.existsByCommunityIdAndReversedOfId(payment.getCommunityId(), payment.getId());
        return new PaymentResult(Views.payment(payment, reversed ? Set.of(payment.getId()) : Set.of()), payment.getInvoice() == null ? null : Views.invoice(payment.getInvoice()), true);
    }

    private void confirm(UUID communityId, UUID invoiceId, Money amount, PaymentMethod method, String reference) {
        PaymentGateway.GatewayResult result = gateways.forCommunity(communityId).settle(new PaymentGateway.PaymentInstruction(communityId, invoiceId, amount, method, reference));
        if (result.status() != PaymentGateway.Status.CONFIRMED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "The payment was not confirmed" + (result.message() == null ? "." : ": " + result.message()));
        }
    }

    private void queueReceiptEmail(Community community, Receipt receipt, PaymentRecord payment, BigDecimal balanceAfter) {
        BillingEmails.Outcome outcome = emails.session(community).receipt(receipt, payment, balanceAfter, true);
        if (outcome == BillingEmails.Outcome.QUEUED) {
            receipt.setEmailedAt(clock.instant());
        }
    }

    private static void checkDate(String field, LocalDate date, LocalDate today) {
        if (date.isAfter(today)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": cannot be in the future"));
        }
        if (date.isBefore(today.minusYears(10))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": too far in the past"));
        }
    }

    /** Runs after the surrounding transaction commits; a failure there is logged, never raised (the money is already recorded). */
    private static void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        log.warn("Post-commit task failed (the payment is recorded; the receipt PDF is rendered on demand): {}", e.toString());
                    }
                }
            });
        } else {
            task.run();
        }
    }
}

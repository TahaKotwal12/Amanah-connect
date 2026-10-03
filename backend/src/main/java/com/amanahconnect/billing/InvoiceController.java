package com.amanahconnect.billing;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.billing.BillingDtos.CancelInvoiceRequest;
import com.amanahconnect.billing.BillingDtos.CreateInvoiceRequest;
import com.amanahconnect.billing.BillingDtos.GenerateInvoicesRequest;
import com.amanahconnect.billing.BillingDtos.GenerateResult;
import com.amanahconnect.billing.BillingDtos.InvoiceDetail;
import com.amanahconnect.billing.BillingDtos.InvoiceView;
import com.amanahconnect.billing.BillingDtos.IssueInvoiceRequest;
import com.amanahconnect.billing.BillingDtos.PayLinkView;
import com.amanahconnect.billing.BillingDtos.PaymentResult;
import com.amanahconnect.billing.BillingDtos.RecordPaymentRequest;
import com.amanahconnect.billing.BillingDtos.UpdateInvoiceRequest;
import com.amanahconnect.billing.InvoiceQueries.InvoiceFilter;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/community/invoices")
@Tag(name = "Community · Billing", description = "Fee plans, invoices, payments and receipts (COMMUNITY_ADMIN).")
public class InvoiceController {

    private static final SortWhitelist SORT = SortWhitelist.of(
            Sort.by(Sort.Direction.DESC, "createdAt"),
            Map.of("dueDate", "dueDate", "issuedOn", "issuedOn", "amount", "amount", "invoiceNo", "invoiceNo", "status", "status", "createdAt", "createdAt", "memberName", "memberName"));

    private final InvoiceService invoices;
    private final InvoiceGenerationService generation;
    private final PaymentService payments;

    public InvoiceController(InvoiceService invoices, InvoiceGenerationService generation, PaymentService payments) {
        this.invoices = invoices;
        this.generation = generation;
        this.payments = payments;
    }

    @GetMapping
    @Operation(summary = "List invoices", description = "Filter by status, member, fee plan, kind, period, due-date range and a search (invoice number, member name or number); outstandingOnly shows what is still owed.")
    public PageResponse<InvoiceView> list(
            @CurrentCommunity UUID communityId,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) UUID feePlanId,
            @RequestParam(required = false) FeeKind kind,
            @RequestParam(required = false) @Size(max = 30) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "false") boolean outstandingOnly,
            @Valid PageQuery page) {
        return invoices.list(communityId, new InvoiceFilter(status, memberId, feePlanId, kind, period, dueFrom, dueTo, q, outstandingOnly), SORT.toPageRequest(page));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Invoice detail", description = "The invoice and every payment row against it (including reversals).")
    public InvoiceDetail get(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return invoices.get(communityId, id);
    }

    @PostMapping("/generate")
    @AuditHandledBy("InvoiceGenerationService records INVOICES_GENERATED")
    @Operation(summary = "Bill a fee plan for a period", description = "Idempotent: one invoice per fee plan, member and period, so repeating the call bills nobody twice. Creates ISSUED invoices with gap-free numbers and queues the bill email per member (only those with an address and consent, within the plan's email quota). 201 when invoices were created, 200 when everyone was already billed.")
    public ResponseEntity<GenerateResult> generate(@CurrentCommunity UUID communityId, @Valid @RequestBody GenerateInvoicesRequest body) {
        GenerateResult result = generation.generate(communityId, body);
        return ResponseEntity.status(result.created() > 0 ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @PostMapping
    @AuditHandledBy("InvoiceService records INVOICE_CREATED")
    @Operation(summary = "Create a one-off invoice", description = "For one member. Issued (numbered, emailed) unless draft=true.")
    public ResponseEntity<InvoiceView> create(@CurrentCommunity UUID communityId, @Valid @RequestBody CreateInvoiceRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invoices.create(communityId, body));
    }

    @PatchMapping("/{id}")
    @AuditHandledBy("InvoiceService records INVOICE_UPDATED with before and after")
    @Operation(summary = "Edit a draft invoice", description = "Drafts only. An issued invoice is cancelled and re-issued instead.")
    public InvoiceView update(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody UpdateInvoiceRequest body) {
        return invoices.updateDraft(communityId, id, body);
    }

    @PostMapping("/{id}/issue")
    @AuditHandledBy("InvoiceService records INVOICE_ISSUED")
    @Operation(summary = "Issue a draft", description = "Gives it its number and makes it payable.")
    public InvoiceView issue(@CurrentCommunity UUID communityId, @PathVariable UUID id, @RequestBody(required = false) IssueInvoiceRequest body) {
        return invoices.issue(communityId, id, body == null ? null : body.sendEmail());
    }

    @PostMapping("/{id}/cancel")
    @AuditHandledBy("InvoiceService records INVOICE_CANCELLED")
    @Operation(summary = "Cancel an invoice", description = "A reason is required. Refused while payments stand on it (reverse them first). The invoice keeps its number and stays on record.")
    public InvoiceView cancel(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody CancelInvoiceRequest body) {
        return invoices.cancel(communityId, id, body);
    }

    @PostMapping("/{id}/payments")
    @AuditHandledBy("PaymentService records PAYMENT_RECORDED")
    @Operation(summary = "Record a payment", description = "Manual confirmation: call this after checking that the money arrived. Partial payments are fine; a payment above the balance is refused (422 OVERPAYMENT). Creates the receipt (gap-free number), a PDF, the receipt email and an INCOME ledger entry in one transaction. Send an Idempotency-Key header to make retries safe: the same key and request returns the original result (200, replayed=true); the same key with a different request is 422.")
    public ResponseEntity<PaymentResult> recordPayment(
            @CurrentCommunity UUID communityId,
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RecordPaymentRequest body) {
        PaymentResult result = payments.record(communityId, id, body, idempotencyKey);
        return respond(result);
    }

    @GetMapping(value = "/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "UPI QR code for the balance", description = "A PNG encoding upi://pay?pa=<UPI ID>&pn=<payee>&am=<balance>&cu=INR&tn=<invoice no>. Needs the community's UPI ID and payee name in settings (409 UPI_NOT_CONFIGURED otherwise). Paying does not mark the invoice paid: record the payment after checking your bank or UPI app.")
    public ResponseEntity<byte[]> qr(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(invoices.upiQr(communityId, id));
    }

    @PostMapping("/{id}/pay-link")
    @AuditHandledBy("InvoiceService records PAY_LINK_CREATED")
    @Operation(summary = "Create a payment-page link", description = "A new expiring link to the public payment-info page, to share by any channel. Shown once.")
    public ResponseEntity<PayLinkView> payLink(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invoices.createPayLink(communityId, id));
    }

    @PostMapping("/{id}/resend")
    @AuditHandledBy("InvoiceService records INVOICE_BILL_RESENT")
    @Operation(summary = "Resend the bill email", description = "With a fresh payment link. Needs the member's email address and email quota.")
    public ResponseEntity<Void> resend(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        invoices.resendBill(communityId, id);
        return ResponseEntity.accepted().build();
    }

    static ResponseEntity<PaymentResult> respond(PaymentResult result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(result);
    }
}

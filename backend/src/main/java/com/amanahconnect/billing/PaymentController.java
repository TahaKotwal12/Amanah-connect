package com.amanahconnect.billing;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.billing.BillingDtos.PaymentResult;
import com.amanahconnect.billing.BillingDtos.PaymentView;
import com.amanahconnect.billing.BillingDtos.RecordDonationRequest;
import com.amanahconnect.billing.BillingDtos.ReversePaymentRequest;
import com.amanahconnect.billing.PaymentQueries.PaymentFilter;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/community")
@Tag(name = "Community · Billing", description = "Fee plans, invoices, payments and receipts (COMMUNITY_ADMIN).")
public class PaymentController {

    private static final SortWhitelist SORT = SortWhitelist.of(
            Sort.by(Sort.Direction.DESC, "createdAt"), Map.of("receivedOn", "receivedOn", "amount", "amount", "createdAt", "createdAt"));

    private final PaymentService payments;
    private final PaymentQueries queries;

    public PaymentController(PaymentService payments, PaymentQueries queries) {
        this.payments = payments;
        this.queries = queries;
    }

    @GetMapping("/payments")
    @Operation(summary = "List payment rows", description = "Payments, donations and reversals, filtered by date received, method, member, invoice and kind (PAYMENT, DONATION, REVERSAL).")
    public PageResponse<PaymentView> list(
            @CurrentCommunity UUID communityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) PaymentMethod method,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) UUID invoiceId,
            @RequestParam(required = false) @Pattern(regexp = "PAYMENT|DONATION|REVERSAL") String kind,
            @Valid PageQuery page) {
        return queries.search(communityId, new PaymentFilter(from, to, method, memberId, invoiceId, kind), SORT.toPageRequest(page));
    }

    @GetMapping("/payments/{id}")
    @Operation(summary = "Payment detail")
    public PaymentView get(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return payments.get(communityId, id);
    }

    @PostMapping("/payments/{id}/reverse")
    @AuditHandledBy("PaymentService records PAYMENT_REVERSED")
    @Operation(summary = "Reverse a payment", description = "A reason is required. Creates an offsetting negative payment row and ledger entry and recomputes the invoice; nothing is deleted, and the receipt stays (marked reversed). Supports Idempotency-Key.")
    public ResponseEntity<PaymentResult> reverse(
            @CurrentCommunity UUID communityId,
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReversePaymentRequest body) {
        return InvoiceController.respond(payments.reverse(communityId, id, body, idempotencyKey));
    }

    @PostMapping("/donations")
    @AuditHandledBy("PaymentService records DONATION_RECORDED")
    @Operation(summary = "Record a donation", description = "From a member (memberId) or an anonymous donor (donorName), not tied to an invoice. Gets a receipt and an INCOME ledger entry in the Donations category. Supports Idempotency-Key.")
    public ResponseEntity<PaymentResult> donate(
            @CurrentCommunity UUID communityId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RecordDonationRequest body) {
        return InvoiceController.respond(payments.donate(communityId, body, idempotencyKey));
    }
}

package com.amanahconnect.billing;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.billing.BillingDtos.ReceiptView;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/community/receipts")
@Tag(name = "Community · Billing", description = "Fee plans, invoices, payments and receipts (COMMUNITY_ADMIN).")
public class ReceiptController {

    private static final SortWhitelist SORT = SortWhitelist.of(
            Sort.by(Sort.Direction.DESC, "createdAt"), Map.of("receiptNo", "receiptNo", "amount", "amount", "receivedOn", "receivedOn", "createdAt", "createdAt"));

    private final ReceiptService receipts;
    private final ReceiptPdfService pdfs;

    public ReceiptController(ReceiptService receipts, ReceiptPdfService pdfs) {
        this.receipts = receipts;
        this.pdfs = pdfs;
    }

    @GetMapping
    @Operation(summary = "List receipts", description = "By date received, search (receipt number, payer, invoice number) and reversed or not.")
    public PageResponse<ReceiptView> list(
            @CurrentCommunity UUID communityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Boolean reversed,
            @Valid PageQuery page) {
        return receipts.list(communityId, from, to, q, reversed, SORT.toPageRequest(page));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Receipt detail")
    public ReceiptView get(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return receipts.get(communityId, id);
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Receipt PDF", description = "The receipt as a PDF (rendered again if the stored copy is missing; a reversed receipt carries a REVERSED banner).")
    public ResponseEntity<byte[]> pdf(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        ReceiptView view = receipts.get(communityId, id);
        byte[] bytes = pdfs.pdf(communityId, id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("receipt-" + view.receiptNo().replace('/', '-') + ".pdf").build().toString()).body(bytes);
    }

    @PostMapping("/{id}/resend")
    @AuditHandledBy("ReceiptService records RECEIPT_RESENT")
    @Operation(summary = "Resend the receipt email", description = "Needs the member's email address and email quota. A reversed receipt is not re-sent.")
    public ResponseEntity<Void> resend(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        receipts.resend(communityId, id);
        return ResponseEntity.accepted().build();
    }
}

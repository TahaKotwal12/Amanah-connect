package com.amanahconnect.ledger;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.ledger.LedgerDtos.AttachmentUploadRequest;
import com.amanahconnect.ledger.LedgerDtos.AttachmentUploadView;
import com.amanahconnect.ledger.LedgerDtos.CategoryView;
import com.amanahconnect.ledger.LedgerDtos.CreateCategoryRequest;
import com.amanahconnect.ledger.LedgerDtos.CreateEntryRequest;
import com.amanahconnect.ledger.LedgerDtos.EntryView;
import com.amanahconnect.ledger.LedgerDtos.ReverseEntryRequest;
import com.amanahconnect.ledger.LedgerDtos.SummaryView;
import com.amanahconnect.ledger.LedgerDtos.UpdateCategoryRequest;
import com.amanahconnect.ledger.LedgerDtos.UpdateEntryRequest;
import com.amanahconnect.ledger.LedgerQueries.EntryFilter;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/community/ledger")
@Tag(name = "Community · Ledger", description = "Income and expense categories, entries and the summary (COMMUNITY_ADMIN).")
public class LedgerController {

    private static final SortWhitelist SORT = SortWhitelist.of(
            Sort.by(Sort.Direction.DESC, "entryDate"), Map.of("entryDate", "entryDate", "amount", "amount", "createdAt", "createdAt", "title", "title"));

    private final LedgerService service;

    public LedgerController(LedgerService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    @Operation(summary = "List categories", description = "Seeded from the generic defaults when the community was created. Hidden ones only with includeInactive=true.")
    public List<CategoryView> categories(@CurrentCommunity UUID communityId, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.categories(communityId, includeInactive);
    }

    @PostMapping("/categories")
    @AuditHandledBy("LedgerService records LEDGER_CATEGORY_CREATED")
    @Operation(summary = "Add a category")
    public ResponseEntity<CategoryView> createCategory(@CurrentCommunity UUID communityId, @Valid @RequestBody CreateCategoryRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCategory(communityId, body));
    }

    @PatchMapping("/categories/{id}")
    @AuditHandledBy("LedgerService records LEDGER_CATEGORY_UPDATED")
    @Operation(summary = "Rename or hide a category", description = "Hiding keeps every past entry. The categories payments post to can be renamed but not hidden.")
    public CategoryView updateCategory(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest body) {
        return service.updateCategory(communityId, id, body);
    }

    @GetMapping("/entries")
    @Operation(summary = "List ledger entries", description = "Filter by date range, type, category, source (MANUAL or PAYMENT) and title search.")
    public PageResponse<EntryView> list(
            @CurrentCommunity UUID communityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) LedgerType type,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) LedgerSource source,
            @RequestParam(required = false) @Size(max = 100) String q,
            @Valid PageQuery page) {
        return service.list(communityId, new EntryFilter(from, to, type, categoryId, source, q), SORT.toPageRequest(page));
    }

    @GetMapping("/entries/{id}")
    @Operation(summary = "Ledger entry detail")
    public EntryView get(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.get(communityId, id);
    }

    @PostMapping("/entries")
    @AuditHandledBy("LedgerService records LEDGER_ENTRY_CREATED")
    @Operation(summary = "Add a manual entry", description = "Income or expense in an active category of the same type, with an optional attachment (upload it first through /ledger/attachments/upload-url).")
    public ResponseEntity<EntryView> create(@CurrentCommunity UUID communityId, @Valid @RequestBody CreateEntryRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(communityId, body));
    }

    @PatchMapping("/entries/{id}")
    @AuditHandledBy("LedgerService records LEDGER_ENTRY_UPDATED with before and after")
    @Operation(summary = "Edit a manual entry", description = "Category (same type), date, title, notes and attachment. The amount never changes: reverse the entry and add the right one. Entries from payments are read-only (409).")
    public EntryView update(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody UpdateEntryRequest body) {
        return service.update(communityId, id, body);
    }

    @PostMapping("/entries/{id}/reverse")
    @AuditHandledBy("LedgerService records LEDGER_ENTRY_REVERSED")
    @Operation(summary = "Reverse a manual entry", description = "A reason is required. Adds a negative twin; nothing is deleted. Payment entries are reversed by reversing the payment.")
    public ResponseEntity<EntryView> reverse(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody ReverseEntryRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reverse(communityId, id, body));
    }

    @PostMapping("/attachments/upload-url")
    @AuditHandledBy("LedgerService records LEDGER_ATTACHMENT_UPLOAD_REQUESTED")
    @Operation(summary = "Get a signed URL to upload a receipt image or PDF", description = "PNG, JPEG, WebP or PDF up to the configured size. PUT the file with exactly the returned headers, then send the attachmentKey with the entry.")
    public AttachmentUploadView attachmentUploadUrl(@CurrentCommunity UUID communityId, @Valid @RequestBody AttachmentUploadRequest body) {
        return service.attachmentUploadUrl(communityId, body);
    }

    @GetMapping("/summary")
    @Operation(summary = "Ledger summary", description = "Total income, expense and net (reversals net out), by category and by month, for a date range (default: all time), with the opening balance and closing balance.")
    public SummaryView summary(
            @CurrentCommunity UUID communityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.summary(communityId, from, to);
    }
}

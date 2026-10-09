package com.amanahconnect.audit;

import com.amanahconnect.audit.AuditDtos.AdminEntry;
import com.amanahconnect.audit.AuditDtos.Filter;
import com.amanahconnect.audit.AuditDtos.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@Validated
@RequestMapping("/api/v1/admin/audit")
@Tag(name = "Admin · Audit", description = "The platform's audit trail: search, page and export (SUPER_ADMIN, 2FA completed). The trail is append-only.")
public class AdminAuditController {

    private final AuditReadService service;

    public AdminAuditController(AuditReadService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Search the audit trail",
            description = "Newest first. Filters (all optional, combined with AND): actor (user id), communityId, action (exact) or actionPrefix (e.g. PAYMENT_), entityType, entityId, from and to "
                    + "(a date such as 2026-05-01 in India time, or a UTC time; a date in 'to' includes that whole day). Pages by keyset: pass nextCursor back as cursor to get the next page; "
                    + "it never skips or repeats a row. limit is 1 to 200 (default 50).")
    public Page<AdminEntry> search(
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) UUID communityId,
            @RequestParam(required = false) @Size(max = 100) String action,
            @RequestParam(required = false) @Size(max = 100) String actionPrefix,
            @RequestParam(required = false) @Size(max = 100) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) @Size(max = 40) String from,
            @RequestParam(required = false) @Size(max = 40) String to,
            @RequestParam(required = false) @Size(max = 200) String cursor,
            @RequestParam(required = false) @Min(1) @Max(200) Integer limit) {
        Filter filter = AuditFilters.of(actor, communityId, action, actionPrefix, entityType, entityId, from, to);
        return service.admin(filter, cursor, limit);
    }

    @GetMapping("/export")
    @Operation(summary = "Download the audit trail as CSV",
            description = "Same filters as the search. Streamed row by row, at most 100,000 rows (newest first); X-Export-Rows says how many and X-Export-Truncated whether more matched. "
                    + "The download is itself recorded in the audit trail (AUDIT_EXPORTED).")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) UUID communityId,
            @RequestParam(required = false) @Size(max = 100) String action,
            @RequestParam(required = false) @Size(max = 100) String actionPrefix,
            @RequestParam(required = false) @Size(max = 100) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) @Size(max = 40) String from,
            @RequestParam(required = false) @Size(max = 40) String to) {
        Filter filter = AuditFilters.of(actor, communityId, action, actionPrefix, entityType, entityId, from, to);
        AuditReadService.ExportPlan plan = service.prepareExport(filter);
        StreamingResponseBody body = out -> service.writeCsv(plan, out);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("audit-" + LocalDate.now(ZoneId.of("Asia/Kolkata")) + ".csv").build().toString())
                .header("X-Export-Rows", Integer.toString(plan.rows()))
                .header("X-Export-Truncated", Boolean.toString(plan.truncated()))
                .body(body);
    }
}

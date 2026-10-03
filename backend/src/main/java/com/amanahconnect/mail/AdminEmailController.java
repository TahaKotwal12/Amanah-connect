package com.amanahconnect.mail;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.mail.AdminEmailDtos.AddSuppressionRequest;
import com.amanahconnect.mail.AdminEmailDtos.OutboxItem;
import com.amanahconnect.mail.AdminEmailDtos.OutboxStats;
import com.amanahconnect.mail.AdminEmailDtos.SuppressionView;
import com.amanahconnect.mail.AdminEmailDtos.TemplateView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/email")
@Tag(name = "Admin · Email", description = "Email templates (preview in dev and staging), the outbox and the suppression list (SUPER_ADMIN, 2FA completed).")
public class AdminEmailController {

    private final AdminEmailService service;

    public AdminEmailController(AdminEmailService service) {
        this.service = service;
    }

    @GetMapping("/templates")
    @Operation(summary = "List email templates", description = "Only where previews are enabled (app.email.preview-enabled: local and staging). In production this answers 404.")
    public List<TemplateView> templates() {
        return service.templates();
    }

    @GetMapping(value = "/templates/{name}/preview", produces = {MediaType.TEXT_HTML_VALUE, MediaType.TEXT_PLAIN_VALUE})
    @Operation(summary = "Preview a template with sample data", description = "format=html (default) or text. The subject is in the X-Email-Subject header. Sample data only: nothing is sent. 404 in production.")
    public ResponseEntity<String> preview(@PathVariable String name, @RequestParam(defaultValue = "html") String format) {
        RenderedMail mail = service.preview(name);
        boolean text = format.equalsIgnoreCase("text");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(text ? MediaType.parseMediaType("text/plain;charset=UTF-8") : MediaType.parseMediaType("text/html;charset=UTF-8"))
                .header("X-Email-Subject", mail.subject().replaceAll("[^\\x20-\\x7E]", "?"))
                .header("Content-Security-Policy", "default-src 'none'; img-src data: cid:; style-src 'unsafe-inline'")
                .body(text ? mail.text() : mail.html());
    }

    @GetMapping("/outbox")
    @Operation(summary = "The email outbox", description = "Newest first. Filters: status (PENDING, SENT, FAILED), template, communityId. Addresses are masked.")
    public PageResponse<OutboxItem> outbox(
            @RequestParam(required = false) String status, @RequestParam(required = false) String template, @RequestParam(required = false) UUID communityId, @Valid PageQuery page) {
        if (status != null && !List.of("PENDING", "SENT", "FAILED").contains(status)) {
            throw new com.amanahconnect.common.error.ApiException(com.amanahconnect.common.error.ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("status: must be PENDING, SENT or FAILED"));
        }
        SortWhitelist.of(Sort.by(Sort.Direction.DESC, "createdAt"), "createdAt").toPageRequest(page);
        return service.outbox(status, template, communityId, PageRequest.of(page.pageOrDefault(), page.sizeOrDefault()));
    }

    @GetMapping("/stats")
    @Operation(summary = "Outbox health", description = "Pending and failed counts, sent in the last 24 hours, the age of the oldest pending mail and the number of suppressed addresses.")
    public OutboxStats stats() {
        return service.stats();
    }

    @PostMapping("/outbox/{id}/retry")
    @AuditHandledBy("AdminEmailService records EMAIL_RETRY_REQUESTED")
    @Operation(summary = "Retry a failed email", description = "Puts a FAILED email back in the queue with its attempts reset. Any other email is a 404.")
    public OutboxItem retry(@PathVariable UUID id) {
        return service.retry(id);
    }

    @GetMapping("/suppressions")
    @Operation(summary = "The suppression list", description = "Addresses the app will never email: hard bounces, complaints, and ones added by hand. Addresses are masked.")
    public PageResponse<SuppressionView> suppressions(@Valid PageQuery page) {
        return service.suppressions(PageRequest.of(page.pageOrDefault(), page.sizeOrDefault(), Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @PostMapping("/suppressions")
    @AuditHandledBy("SuppressionService records EMAIL_SUPPRESSED")
    @Operation(summary = "Suppress an address by hand")
    public Map<String, Object> addSuppression(@Valid @RequestBody AddSuppressionRequest body) {
        return service.addSuppression(body.email());
    }

    @DeleteMapping("/suppressions/{email}")
    @AuditHandledBy("SuppressionService records EMAIL_SUPPRESSION_REMOVED")
    @Operation(summary = "Remove an address from the suppression list", description = "Use when an address was suppressed by mistake. Emails to it start flowing again.")
    public ResponseEntity<Void> removeSuppression(@PathVariable String email) {
        service.removeSuppression(email);
        return ResponseEntity.noContent().build();
    }
}

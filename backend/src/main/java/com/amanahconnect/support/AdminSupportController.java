package com.amanahconnect.support;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.Priority;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.support.SupportDtos.AttachmentUploadRequest;
import com.amanahconnect.support.SupportDtos.AttachmentUploadView;
import com.amanahconnect.support.SupportDtos.MarkReadRequest;
import com.amanahconnect.support.SupportDtos.MessagePage;
import com.amanahconnect.support.SupportDtos.MessageView;
import com.amanahconnect.support.SupportDtos.PlatformCounts;
import com.amanahconnect.support.SupportDtos.PostMessageRequest;
import com.amanahconnect.support.SupportDtos.Summary;
import com.amanahconnect.support.SupportDtos.ThreadView;
import com.amanahconnect.support.SupportDtos.UpdateThreadRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
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

/** The platform's end of the helpdesk: every community's threads. SUPER_ADMIN only (the /admin prefix is enforced in the security config). */
@RestController
@Validated
@RequestMapping("/api/v1/admin/support")
@Tag(name = "Admin · Support desk", description = "Answer, assign and close community admins' support threads (SUPER_ADMIN, 2FA completed).")
public class AdminSupportController {

    private final SupportService service;

    public AdminSupportController(SupportService service) {
        this.service = service;
    }

    @GetMapping("/threads")
    @Operation(summary = "All support threads",
            description = "Filters: status (repeatable), priority, communityId, assignedTo, unassigned, mine, unread (customer replies not read yet) and q (subject or community name). Sort by lastMessageAt (default), createdAt, status, subject, community or priority.")
    public PageResponse<ThreadView> list(
            @RequestParam(required = false) List<ThreadStatus> status,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) UUID communityId,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(required = false, defaultValue = "false") boolean unassigned,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @RequestParam(required = false, defaultValue = "false") boolean unread,
            @RequestParam(required = false) @Size(max = 100) String q,
            @Valid PageQuery page) {
        return service.list(SupportViewer.platform(),
                new SupportQueries.Filter(status, priority, communityId, assignedTo, unassigned, mine ? AuditService.currentActorId() : null, unread, q),
                SupportController.SORT.toPageRequest(page));
    }

    @GetMapping("/counts")
    @Operation(summary = "Desk counts", description = "By status, plus active threads nobody has picked up, urgent ones and threads with unread customer messages.")
    public PlatformCounts counts() {
        return service.platformCounts();
    }

    @GetMapping("/summary")
    @Operation(summary = "Unread counters and thread positions", description = "Same shape as the community side, from the support team's point of view: unread means customer messages not yet read.")
    public Summary summary() {
        return service.summary(SupportViewer.platform());
    }

    @GetMapping("/threads/{id}")
    @Operation(summary = "Thread detail")
    public ThreadView get(@PathVariable UUID id) {
        return service.get(SupportViewer.platform(), id);
    }

    @PatchMapping("/threads/{id}")
    @AuditHandledBy("SupportService records SUPPORT_THREAD_UPDATED with before and after")
    @Operation(summary = "Assign, change priority or status, close", description = "Partial: status (OPEN, WAITING, RESOLVED, CLOSED), priority, assignedTo (an active super admin) or unassign=true.")
    public ThreadView update(@PathVariable UUID id, @Valid @RequestBody UpdateThreadRequest body) {
        return service.update(id, body);
    }

    @GetMapping("/threads/{id}/messages")
    @Operation(summary = "Messages after a cursor", description = "Same polling contract as the community side: after=<cursor>, oldest first, hasMore. Does not mark anything read.")
    public MessagePage messages(@PathVariable UUID id, @RequestParam(defaultValue = "0") @Min(0) long after, @RequestParam(required = false) @Min(1) @Max(200) Integer limit) {
        return service.messages(SupportViewer.platform(), id, after, limit);
    }

    @PostMapping("/threads/{id}/messages")
    @AuditHandledBy("SupportService records SUPPORT_MESSAGE_POSTED")
    @Operation(summary = "Reply", description = "Sets the thread WAITING (for the community) and emails the community's admins once per burst. A closed thread answers 409 THREAD_CLOSED.")
    public ResponseEntity<MessageView> post(@PathVariable UUID id, @Valid @RequestBody PostMessageRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.post(SupportViewer.platform(), id, body));
    }

    @PostMapping("/threads/{id}/read")
    @AuditHandledBy("SupportService records SUPPORT_THREAD_READ, only when something was actually unread")
    @Operation(summary = "Mark the community's messages as read")
    public Summary markRead(@PathVariable UUID id, @Valid @RequestBody(required = false) MarkReadRequest body) {
        return service.markRead(SupportViewer.platform(), id, body);
    }

    @PostMapping("/threads/{id}/attachments/upload-url")
    @AuditHandledBy("SupportService records SUPPORT_ATTACHMENT_UPLOAD_REQUESTED")
    @Operation(summary = "Signed upload URL for an attachment on this thread")
    public AttachmentUploadView uploadUrl(@PathVariable UUID id, @Valid @RequestBody AttachmentUploadRequest body) {
        return service.uploadUrlForThread(id, body);
    }
}

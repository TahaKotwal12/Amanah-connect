package com.amanahconnect.support;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.support.SupportDtos.AttachmentUploadRequest;
import com.amanahconnect.support.SupportDtos.AttachmentUploadView;
import com.amanahconnect.support.SupportDtos.CreateThreadRequest;
import com.amanahconnect.support.SupportDtos.MarkReadRequest;
import com.amanahconnect.support.SupportDtos.MessagePage;
import com.amanahconnect.support.SupportDtos.MessageView;
import com.amanahconnect.support.SupportDtos.PostMessageRequest;
import com.amanahconnect.support.SupportDtos.Summary;
import com.amanahconnect.support.SupportDtos.ThreadView;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The community admin's end of the helpdesk. Only this community's threads exist as far as these endpoints are concerned. */
@RestController
@Validated
@RequestMapping("/api/v1/community/support")
@Tag(name = "Community · Support chat", description = "Talk to the platform's support team (COMMUNITY_ADMIN). Poll GET .../messages?after=<cursor> every 10 seconds for new messages.")
public class SupportController {

    static final SortWhitelist SORT = SortWhitelist.of(Sort.by(Sort.Direction.DESC, "lastMessageAt"), "lastMessageAt", "createdAt", "status", "subject", "priority");

    private final SupportService service;

    public SupportController(SupportService service) {
        this.service = service;
    }

    @GetMapping("/threads")
    @Operation(summary = "My community's support threads", description = "Newest activity first. Filters: status (repeatable), unread=true (only threads with replies you have not read), q (subject). Each thread carries unreadCount and a preview of the last message.")
    public PageResponse<ThreadView> list(
            @CurrentCommunity UUID communityId,
            @RequestParam(required = false) List<ThreadStatus> status,
            @RequestParam(required = false, defaultValue = "false") boolean unread,
            @RequestParam(required = false) @Size(max = 100) String q,
            @Valid PageQuery page) {
        return service.list(SupportViewer.community(communityId), new SupportQueries.Filter(status, null, null, null, false, null, unread, q), SORT.toPageRequest(page));
    }

    @GetMapping("/threads/{id}")
    @Operation(summary = "Thread detail")
    public ThreadView get(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.get(SupportViewer.community(communityId), id);
    }

    @PostMapping("/threads")
    @AuditHandledBy("SupportService records SUPPORT_THREAD_CREATED")
    @Operation(summary = "Start a conversation", description = "Subject, priority (default MEDIUM) and the first message, optionally with an attachment (upload it first with POST /attachments/upload-url). Emails the support team.")
    public ResponseEntity<ThreadView> create(@CurrentCommunity UUID communityId, @Valid @RequestBody CreateThreadRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createThread(communityId, body));
    }

    @GetMapping("/threads/{id}/messages")
    @Operation(summary = "Messages after a cursor",
            description = "The polling endpoint. Pass after=0 (the default) for the whole conversation, then after=<cursor from the previous answer> every 10 seconds. "
                    + "Items come oldest first; cursor only moves forward; hasMore says to ask again straight away. Also returns the thread's current status. Reading does not mark messages read: call POST .../read when the conversation is on screen.")
    public MessagePage messages(
            @CurrentCommunity UUID communityId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(required = false) @Min(1) @Max(200) Integer limit) {
        return service.messages(SupportViewer.community(communityId), id, after, limit);
    }

    @PostMapping("/threads/{id}/messages")
    @AuditHandledBy("SupportService records SUPPORT_MESSAGE_POSTED")
    @Operation(summary = "Send a message", description = "Reopens a resolved thread. A closed thread answers 409 THREAD_CLOSED. The support team is emailed once per burst of messages, not once per message.")
    public ResponseEntity<MessageView> post(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody PostMessageRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.post(SupportViewer.community(communityId), id, body));
    }

    @PostMapping("/threads/{id}/read")
    @AuditHandledBy("SupportService records SUPPORT_THREAD_READ, only when something was actually unread")
    @Operation(summary = "Mark the support team's messages as read", description = "Optional body {\"upToSeq\": n}; default is everything so far. Returns the new summary.")
    public Summary markRead(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody(required = false) MarkReadRequest body) {
        return service.markRead(SupportViewer.community(communityId), id, body);
    }

    @GetMapping("/summary")
    @Operation(summary = "Unread counters and thread positions", description = "Cheap to poll: unreadMessages and unreadThreads for a badge, and for each live thread its latest message seq, status and unread count. Compare messageSeq with your cursor to know which thread to fetch.")
    public Summary summary(@CurrentCommunity UUID communityId) {
        return service.summary(SupportViewer.community(communityId));
    }

    @PostMapping("/attachments/upload-url")
    @AuditHandledBy("SupportService records SUPPORT_ATTACHMENT_UPLOAD_REQUESTED")
    @Operation(summary = "Signed upload URL for an attachment", description = "PNG, JPEG, WebP or PDF, at most 5 MB. PUT the file to uploadUrl with the headers, then send attachmentKey with the message.")
    public AttachmentUploadView uploadUrl(@CurrentCommunity UUID communityId, @Valid @RequestBody AttachmentUploadRequest body) {
        return service.uploadUrl(communityId, body);
    }
}

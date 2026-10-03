package com.amanahconnect.support;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.auth.UserRole;
import com.amanahconnect.auth.UserStatus;
import com.amanahconnect.common.Priority;
import com.amanahconnect.common.Text;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.support.SupportDtos.AttachmentUploadRequest;
import com.amanahconnect.support.SupportDtos.AttachmentUploadView;
import com.amanahconnect.support.SupportDtos.CreateThreadRequest;
import com.amanahconnect.support.SupportDtos.MarkReadRequest;
import com.amanahconnect.support.SupportDtos.MessagePage;
import com.amanahconnect.support.SupportDtos.MessageView;
import com.amanahconnect.support.SupportDtos.PlatformCounts;
import com.amanahconnect.support.SupportDtos.PostMessageRequest;
import com.amanahconnect.support.SupportDtos.Summary;
import com.amanahconnect.support.SupportDtos.ThreadState;
import com.amanahconnect.support.SupportDtos.ThreadView;
import com.amanahconnect.support.SupportDtos.UpdateThreadRequest;
import com.amanahconnect.support.SupportQueries.Filter;
import com.amanahconnect.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The helpdesk between a community's admins and the platform's super admins. Every method takes a {@link SupportViewer}: a
 * community viewer reaches only its own community's threads (anything else is a 404), the platform viewer reaches all.
 *
 * <p>Posting locks the thread row, so messages get seq 1, 2, 3... in the order they commit and a poller asking for
 * "after seq N" can never skip one. Statuses: a community message (re)opens the thread (OPEN); a platform reply sets it
 * WAITING (for the community); super admins can also set RESOLVED or CLOSED. A CLOSED thread takes no more messages.
 */
@Service
@Transactional
public class SupportService {

    private static final int MAX_PAGE = 200;

    private final SupportThreadRepository threads;
    private final SupportMessageRepository messages;
    private final SupportQueries queries;
    private final SupportAttachments attachments;
    private final SupportEmails emails;
    private final CommunityRepository communities;
    private final UserRepository users;
    private final AuditService audit;
    private final Clock clock;
    private final EntityManager em;

    public SupportService(
            SupportThreadRepository threads,
            SupportMessageRepository messages,
            SupportQueries queries,
            SupportAttachments attachments,
            SupportEmails emails,
            CommunityRepository communities,
            UserRepository users,
            AuditService audit,
            Clock clock,
            EntityManager em) {
        this.threads = threads;
        this.messages = messages;
        this.queries = queries;
        this.attachments = attachments;
        this.emails = emails;
        this.communities = communities;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
        this.em = em;
    }

    // ---- reads ------------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<ThreadView> list(SupportViewer viewer, Filter filter, Pageable page) {
        return queries.search(viewer, filter, page);
    }

    @Transactional(readOnly = true)
    public ThreadView get(SupportViewer viewer, UUID id) {
        return queries.find(viewer, id).orElseThrow(NotFoundException::new);
    }

    /** New messages after a cursor (a seq). Reading does not mark anything as read: a background poll must not count as seen. */
    @Transactional(readOnly = true)
    public MessagePage messages(SupportViewer viewer, UUID threadId, long after, Integer limit) {
        ThreadView thread = get(viewer, threadId);
        int size = limit == null ? 100 : Math.min(Math.max(limit, 1), MAX_PAGE);
        List<MessageView> rows = queries.messagesAfter(thread.communityId(), threadId, Math.max(after, 0), size + 1);
        boolean more = rows.size() > size;
        List<MessageView> items = more ? rows.subList(0, size) : rows;
        long cursor = items.isEmpty() ? Math.max(after, 0) : items.get(items.size() - 1).seq();
        return new MessagePage(items, cursor, more, new ThreadState(thread.status(), thread.priority(), thread.assignedTo(), thread.messageCount(), thread.lastMessageAt()));
    }

    @Transactional(readOnly = true)
    public Summary summary(SupportViewer viewer) {
        return queries.summary(viewer);
    }

    @Transactional(readOnly = true)
    public PlatformCounts platformCounts() {
        return queries.platformCounts();
    }

    // ---- writes -----------------------------------------------------------------------------------------------------------

    public ThreadView createThread(UUID communityId, CreateThreadRequest request) {
        SupportThread thread = new SupportThread();
        thread.setCommunityId(communityId);
        thread.setSubject(subject(request.subject()));
        thread.setPriority(request.priority() == null ? Priority.MEDIUM : request.priority());
        thread.setStatus(ThreadStatus.OPEN);
        thread.setCreatedBy(AuditService.currentActorId());
        threads.save(thread);
        em.flush();
        SupportMessage first = appendMessage(thread, SupportSide.COMMUNITY, request.body(), request.attachmentKey(), request.attachmentName(), clock.instant());
        em.flush();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("subject", thread.getSubject());
        after.put("priority", thread.getPriority().name());
        audit.record("SUPPORT_THREAD_CREATED", "SupportThread", thread.getId(), null, after);
        notify(thread, SupportSide.COMMUNITY, 0, first.getBody());
        return get(SupportViewer.community(communityId), thread.getId());
    }

    public MessageView post(SupportViewer viewer, UUID threadId, PostMessageRequest request) {
        SupportThread thread = lock(viewer, threadId);
        if (thread.getStatus() == ThreadStatus.CLOSED) {
            throw new ApiException(ErrorCode.THREAD_CLOSED, "This conversation is closed. Start a new one.");
        }
        Instant now = clock.instant();
        long unreadBefore = messages.countByCommunityIdAndThreadIdAndSenderSideAndReadAtIsNull(thread.getCommunityId(), thread.getId(), viewer.side());
        ThreadStatus before = thread.getStatus();
        SupportMessage message = appendMessage(thread, viewer.side(), request.body(), request.attachmentKey(), request.attachmentName(), now);
        if (viewer.side() == SupportSide.COMMUNITY) {
            thread.setStatus(ThreadStatus.OPEN);
            thread.setClosedAt(null);
        } else {
            thread.setStatus(ThreadStatus.WAITING);
        }
        threads.save(thread);
        em.flush();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("threadId", thread.getId().toString());
        after.put("seq", message.getSeq());
        after.put("side", viewer.side().name());
        after.put("hasAttachment", message.getAttachmentKey() != null);
        after.put("statusBefore", before.name());
        after.put("statusAfter", thread.getStatus().name());
        record(viewer, "SUPPORT_MESSAGE_POSTED", thread.getCommunityId(), "SupportMessage", message.getId(), null, after);
        notify(thread, viewer.side(), unreadBefore, message.getBody());
        return queries.messagesAfter(thread.getCommunityId(), thread.getId(), message.getSeq() - 1, 1).get(0);
    }

    /** Marks what the other side wrote as read (all of it, or up to a seq). Nothing changes, and nothing is audited, if nothing was unread. */
    public Summary markRead(SupportViewer viewer, UUID threadId, MarkReadRequest request) {
        ThreadView thread = get(viewer, threadId);
        long upTo = request == null || request.upToSeq() == null ? Long.MAX_VALUE : request.upToSeq();
        int changed = messages.markReadByCommunityId(thread.communityId(), threadId, viewer.side().other(), upTo, clock.instant());
        if (changed > 0) {
            record(viewer, "SUPPORT_THREAD_READ", thread.communityId(), "SupportThread", threadId, null, Map.of("messagesMarkedRead", changed, "side", viewer.side().name()));
        }
        return queries.summary(viewer);
    }

    /** Platform side: status, priority, assignee. */
    public ThreadView update(UUID threadId, UpdateThreadRequest request) {
        SupportViewer platform = SupportViewer.platform();
        SupportThread thread = lock(platform, threadId);
        Map<String, Object> before = snapshot(thread);
        Instant now = clock.instant();
        if (request.priority() != null) thread.setPriority(request.priority());
        if (Boolean.TRUE.equals(request.unassign())) {
            if (request.assignedTo() != null) throw invalid("assignedTo", "cannot be combined with unassign");
            thread.setAssignedTo(null);
        } else if (request.assignedTo() != null) {
            if (!users.existsByIdAndRoleAndStatus(request.assignedTo(), UserRole.SUPER_ADMIN, UserStatus.ACTIVE)) {
                throw invalid("assignedTo", "must be an active super admin");
            }
            thread.setAssignedTo(request.assignedTo());
        }
        if (request.status() != null && request.status() != thread.getStatus()) {
            thread.setStatus(request.status());
            thread.setClosedAt(request.status() == ThreadStatus.CLOSED ? now : null);
        }
        threads.save(thread);
        em.flush();
        record(platform, "SUPPORT_THREAD_UPDATED", thread.getCommunityId(), "SupportThread", threadId, before, snapshot(thread));
        return get(platform, threadId);
    }

    /** Presigned upload for a chat attachment in the community's own space. */
    public AttachmentUploadView uploadUrl(UUID communityId, AttachmentUploadRequest request) {
        AttachmentUploadView view = attachments.uploadUrl(communityId, request);
        audit.recordForCommunity("SUPPORT_ATTACHMENT_UPLOAD_REQUESTED", communityId, "SupportMessage", null, null, Map.of("attachmentKey", view.attachmentKey()));
        return view;
    }

    /** The platform uploads into a thread's community space. */
    public AttachmentUploadView uploadUrlForThread(UUID threadId, AttachmentUploadRequest request) {
        ThreadView thread = get(SupportViewer.platform(), threadId);
        return uploadUrl(thread.communityId(), request);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private SupportMessage appendMessage(SupportThread thread, SupportSide side, String body, String attachmentKey, String attachmentName, Instant now) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) throw invalid("body", "must not be blank");
        SupportAttachments.Accepted file = attachments.accept(thread.getCommunityId(), attachmentKey, attachmentName);
        long seq = thread.getMessageSeq() + 1;
        SupportMessage message = new SupportMessage();
        message.setCommunityId(thread.getCommunityId());
        message.setThread(thread);
        message.setSenderUserId(AuditService.currentActorId());
        message.setSenderSide(side);
        message.setSeq(seq);
        message.setBody(text);
        if (file != null) {
            message.setAttachmentKey(file.key());
            message.setAttachmentName(file.name());
            message.setAttachmentContentType(file.contentType());
            message.setAttachmentSize(file.size());
        }
        messages.save(message);
        thread.setMessageSeq(seq);
        thread.setLastMessageAt(now);
        return message;
    }

    private void notify(SupportThread thread, SupportSide from, long unreadBefore, String body) {
        String communityName = communities.findById(thread.getCommunityId()).map(c -> c.getName()).orElse("");
        String senderName = users.findById(AuditService.currentActorId()).map(u -> u.getFullName()).orElse("");
        emails.onMessage(thread, communityName, from, unreadBefore, senderName, body, clock.instant());
        threads.save(thread);
    }

    private SupportThread lock(SupportViewer viewer, UUID id) {
        return (viewer.isPlatform() ? threads.findWithLockForPlatform(id) : threads.findWithLockByIdAndCommunityId(id, viewer.communityId())).orElseThrow(NotFoundException::new);
    }

    /** Super admins have no tenant; a community admin's action is recorded against its own community. */
    private void record(SupportViewer viewer, String action, UUID communityId, String entityType, UUID entityId, Object before, Object after) {
        if (TenantContext.current().isPresent()) {
            audit.record(action, entityType, entityId, before, after);
        } else {
            audit.recordForCommunity(action, communityId, entityType, entityId, before, after);
        }
    }

    private static String subject(String value) {
        String cleaned = Text.singleLine(value);
        if (cleaned.isEmpty()) throw invalid("subject", "must not be blank");
        return cleaned;
    }

    private static Map<String, Object> snapshot(SupportThread t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", t.getStatus().name());
        map.put("priority", t.getPriority().name());
        map.put("assignedTo", t.getAssignedTo() == null ? null : t.getAssignedTo().toString());
        map.put("closedAt", t.getClosedAt() == null ? null : t.getClosedAt().toString());
        return map;
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": " + message));
    }
}

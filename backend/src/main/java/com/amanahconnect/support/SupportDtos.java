package com.amanahconnect.support;

import com.amanahconnect.common.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SupportDtos {

    private SupportDtos() {}

    public record CreateThreadRequest(
            @NotBlank @Size(max = 200) String subject,
            Priority priority,
            @NotBlank @Size(max = 5000) String body,
            @Size(max = 255) String attachmentKey,
            @Size(max = 200) String attachmentName) {}

    public record PostMessageRequest(
            @NotBlank @Size(max = 5000) String body,
            @Size(max = 255) String attachmentKey,
            @Size(max = 200) String attachmentName) {}

    /** Platform side only. Partial. {@code unassign} clears the assignee. */
    public record UpdateThreadRequest(ThreadStatus status, Priority priority, UUID assignedTo, Boolean unassign) {}

    /** Everything up to {@code upToSeq} (default: everything) that the other side wrote is now read. */
    public record MarkReadRequest(Long upToSeq) {}

    public record AttachmentUploadRequest(@NotBlank @Size(max = 100) String contentType, @NotNull Long sizeBytes) {}

    public record AttachmentUploadView(String uploadUrl, String method, Map<String, String> headers, String attachmentKey, Instant expiresAt, long maxBytes) {}

    public record AttachmentView(String name, String contentType, long size, String downloadUrl) {}

    public record ThreadView(
            UUID id,
            UUID communityId,
            String communityName,
            String subject,
            ThreadStatus status,
            Priority priority,
            UUID assignedTo,
            String assignedToName,
            UUID createdBy,
            String createdByName,
            Instant createdAt,
            Instant lastMessageAt,
            Instant closedAt,
            long messageCount,
            /** Messages the OTHER side wrote that the caller's side has not read yet. */
            long unreadCount,
            String lastMessagePreview) {}

    public record MessageView(UUID id, long seq, SupportSide side, UUID senderId, String senderName, String body, AttachmentView attachment, Instant createdAt, Instant readAt) {}

    /**
     * A page of new messages. Ask again with {@code after=cursor}; the cursor only moves forward and a message can never
     * appear behind it. The same items could be pushed over SSE or a WebSocket later, each tagged with its seq.
     */
    public record MessagePage(List<MessageView> items, long cursor, boolean hasMore, ThreadState thread) {}

    public record ThreadState(ThreadStatus status, Priority priority, UUID assignedTo, long messageCount, Instant lastMessageAt) {}

    public record ThreadSummary(UUID id, long messageSeq, ThreadStatus status, long unreadCount, Instant lastMessageAt) {}

    /** The cheap thing to poll: totals for a badge, and where each live thread is (compare messageSeq with your cursor). */
    public record Summary(long unreadMessages, long unreadThreads, long activeThreads, List<ThreadSummary> threads) {}

    public record PlatformCounts(long total, long open, long waiting, long resolved, long closed, long activeUnassigned, long urgentActive, long unreadThreads) {}
}

package com.amanahconnect.complaint;

import com.amanahconnect.common.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class ComplaintDtos {

    private ComplaintDtos() {}

    public enum Visibility { INTERNAL, MEMBER }

    public record CreateComplaintRequest(
            UUID memberId,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 5000) String description,
            Priority priority,
            @Size(max = 60) String category,
            UUID assignedTo) {}

    /** Partial: a missing field is unchanged. {@code unassign} clears the assignee. */
    public record UpdateComplaintRequest(
            @Size(min = 1, max = 200) String subject,
            @Size(min = 1, max = 5000) String description,
            Priority priority,
            @Size(max = 60) String category,
            UUID memberId,
            UUID assignedTo,
            Boolean unassign) {}

    public record ChangeStatusRequest(@NotNull ComplaintStatus status, @Size(max = 2000) String note, Boolean notifyMember) {}

    public record AddCommentRequest(@NotBlank @Size(max = 5000) String body, @NotNull Visibility visibility, Boolean notifyMember) {}

    public record ComplaintView(
            UUID id,
            String subject,
            String description,
            ComplaintStatus status,
            Priority priority,
            String category,
            UUID memberId,
            String memberNo,
            String memberName,
            UUID assignedTo,
            String assignedToName,
            Instant createdAt,
            Instant updatedAt,
            Instant resolvedAt,
            Instant closedAt,
            long ageDays,
            int slaTargetDays,
            boolean slaBreached,
            long commentCount,
            /** Only on the response to a status change or comment that asked to email the member: QUEUED, NO_ADDRESS, NO_CONSENT or QUOTA. */
            String emailOutcome) {

        public ComplaintView withEmailOutcome(String outcome) {
            return new ComplaintView(id, subject, description, status, priority, category, memberId, memberNo, memberName, assignedTo, assignedToName, createdAt, updatedAt, resolvedAt, closedAt,
                    ageDays, slaTargetDays, slaBreached, commentCount, outcome);
        }
    }

    public record CommentView(UUID id, UUID authorId, String authorName, String body, Visibility visibility, Instant createdAt, String emailOutcome) {}

    public record ComplaintCounts(
            long total,
            long open,
            long inProgress,
            long resolved,
            long closed,
            /** Open plus in progress. */
            long active,
            long slaBreached,
            long urgentActive,
            long unassignedActive,
            long assignedToMe,
            Map<String, Long> byCategory,
            Map<String, Long> byPriority) {}
}

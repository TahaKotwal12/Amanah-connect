package com.amanahconnect.announcement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AnnouncementDtos {

    private AnnouncementDtos() {}

    // ---- community announcements ----------------------------------------------------------------------------------------

    /** {@code body} is HTML; it is sanitised against an allow-list on the server and the clean version is what is stored and sent. */
    public record CreateAnnouncementRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20000) String body,
            AnnouncementAudience audience,
            @Size(max = 100) String group,
            @Size(max = 2000) List<UUID> memberIds,
            Boolean sendEmail,
            /** If given (and in the future) the announcement is created SCHEDULED instead of DRAFT. */
            Instant scheduledAt) {}

    /** Partial. {@code unschedule=true} returns a scheduled announcement to DRAFT. */
    public record UpdateAnnouncementRequest(
            @Size(min = 1, max = 200) String title,
            @Size(min = 1, max = 20000) String body,
            AnnouncementAudience audience,
            @Size(max = 100) String group,
            @Size(max = 2000) List<UUID> memberIds,
            Boolean sendEmail,
            Instant scheduledAt,
            Boolean unschedule) {}

    public record ScheduleRequest(@NotNull Instant scheduledAt) {}

    public record DeliveryView(int recipientsTotal, int emailsQueued, int skippedNoEmail, int skippedNoConsent, int skippedQuota) {}

    public record AnnouncementView(
            UUID id,
            String title,
            String bodyHtml,
            String bodyText,
            AnnouncementAudience audience,
            String group,
            List<UUID> memberIds,
            boolean sendEmail,
            AnnouncementStatus status,
            Instant scheduledAt,
            Instant sentAt,
            DeliveryView delivery,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {}

    /** What sending now would do. Nothing is queued by asking. */
    public record PreviewView(
            String title,
            String bodyHtml,
            String bodyText,
            AnnouncementAudience audience,
            boolean sendEmail,
            int audienceSize,
            int eligibleForEmail,
            int noEmail,
            int noConsent,
            /** Emails the plan still allows this month; null when the plan has no limit. */
            Long quotaRemaining,
            int wouldSkipForQuota) {}

    public record TestSendView(String sentTo, boolean queued) {}

    // ---- platform announcements ------------------------------------------------------------------------------------------

    public record CreatePlatformAnnouncementRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20000) String body,
            AnnouncementKind kind,
            /** Empty or missing means every active community. */
            @Size(max = 1000) List<UUID> communityIds,
            Boolean sendEmail,
            /** Also show as an in-app banner to the admins of the targeted communities. */
            Boolean banner,
            /** When the banner and the notification stop being shown (optional). */
            Instant expiresAt,
            Instant scheduledAt) {}

    public record UpdatePlatformAnnouncementRequest(
            @Size(min = 1, max = 200) String title,
            @Size(min = 1, max = 20000) String body,
            AnnouncementKind kind,
            @Size(max = 1000) List<UUID> communityIds,
            Boolean allCommunities,
            Boolean sendEmail,
            Boolean banner,
            Instant expiresAt,
            Boolean clearExpiry,
            Instant scheduledAt,
            Boolean unschedule) {}

    public record PlatformAnnouncementView(
            UUID id,
            String title,
            String bodyHtml,
            String bodyText,
            AnnouncementKind kind,
            /** Null means every active community. */
            List<UUID> communityIds,
            boolean sendEmail,
            boolean banner,
            Instant expiresAt,
            AnnouncementStatus status,
            Instant scheduledAt,
            Instant sentAt,
            DeliveryView delivery,
            long readCount,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {}

    public record PlatformPreviewView(String title, String bodyHtml, String bodyText, AnnouncementKind kind, int communities, int adminRecipients, boolean sendEmail, boolean banner) {}

    // ---- the community admin's notification area -------------------------------------------------------------------------

    public record NotificationView(UUID id, String title, String bodyHtml, String bodyText, AnnouncementKind kind, boolean banner, Instant sentAt, Instant expiresAt, boolean read) {}

    /** The cheap thing to poll: a badge number and the banners still to show. */
    public record NotificationSummary(long unread, List<NotificationView> banners) {}
}

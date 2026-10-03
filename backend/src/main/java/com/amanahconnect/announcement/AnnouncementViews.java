package com.amanahconnect.announcement;

import com.amanahconnect.announcement.AnnouncementDtos.AnnouncementView;
import com.amanahconnect.announcement.AnnouncementDtos.DeliveryView;
import com.amanahconnect.announcement.AnnouncementDtos.PlatformAnnouncementView;
import java.util.List;
import java.util.UUID;

final class AnnouncementViews {

    private AnnouncementViews() {}

    static DeliveryView delivery(Announcement a) {
        return new DeliveryView(a.getRecipientsTotal(), a.getEmailsQueued(), a.getSkippedNoEmail(), a.getSkippedNoConsent(), a.getSkippedQuota());
    }

    static AnnouncementView community(Announcement a) {
        Object group = a.getAudienceFilter().get("group");
        return new AnnouncementView(a.getId(), a.getTitle(), a.getBody(), RichText.text(a.getBody()), a.getAudience(), group == null ? null : String.valueOf(group),
                a.getAudience() == AnnouncementAudience.SELECTED ? AnnouncementDispatcher.memberIds(a) : null, a.isSendEmail(), a.getStatus(), a.getScheduledAt(), a.getSentAt(),
                delivery(a), a.getCreatedBy(), a.getCreatedAt(), a.getUpdatedAt());
    }

    static PlatformAnnouncementView platform(Announcement a, List<UUID> communityIds, long readCount) {
        return new PlatformAnnouncementView(a.getId(), a.getTitle(), a.getBody(), RichText.text(a.getBody()), a.getKind(), communityIds, a.isSendEmail(), a.isBanner(), a.getExpiresAt(),
                a.getStatus(), a.getScheduledAt(), a.getSentAt(), delivery(a), readCount, a.getCreatedBy(), a.getCreatedAt(), a.getUpdatedAt());
    }
}

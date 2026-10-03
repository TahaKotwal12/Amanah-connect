package com.amanahconnect.announcement;

import com.amanahconnect.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A community announcement, or (communityId = null) a platform-wide one to community admins. Because
 * the community is optional this is not a {@code TenantRepository} entity: its repository exposes
 * explicit, community-scoped queries instead.
 */
@Entity
@Table(name = "announcements")
@Getter
@Setter
public class Announcement extends BaseEntity {

    @Column(name = "community_id", updatable = false)
    private UUID communityId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 20)
    private AnnouncementAudience audience;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_filter", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> audienceFilter = new HashMap<>();

    @Column(name = "send_email", nullable = false)
    private boolean sendEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnnouncementStatus status = AnnouncementStatus.DRAFT;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private AnnouncementKind kind = AnnouncementKind.ANNOUNCEMENT;

    /** Platform announcements only: also show as an in-app banner until {@code expiresAt}. */
    @Column(name = "banner", nullable = false)
    private boolean banner;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "recipients_total", nullable = false)
    private int recipientsTotal;

    @Column(name = "emails_queued", nullable = false)
    private int emailsQueued;

    @Column(name = "skipped_no_email", nullable = false)
    private int skippedNoEmail;

    @Column(name = "skipped_no_consent", nullable = false)
    private int skippedNoConsent;

    @Column(name = "skipped_quota", nullable = false)
    private int skippedQuota;
}

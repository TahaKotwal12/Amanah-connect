package com.amanahconnect.announcement;

import com.amanahconnect.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A community admin has seen a platform announcement (the notification area's read state). */
@Entity
@Table(name = "announcement_reads")
@Getter
@Setter
public class AnnouncementRead extends BaseEntity {

    @Column(name = "announcement_id", nullable = false, updatable = false)
    private UUID announcementId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "read_at", nullable = false)
    private Instant readAt = Instant.now();
}

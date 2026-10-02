package com.amanahconnect.support;

import com.amanahconnect.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "support_messages")
@Getter
@Setter
public class SupportMessage extends BaseEntity {

    @Column(name = "community_id", nullable = false, updatable = false)
    private UUID communityId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "thread_id", nullable = false, updatable = false)
    private SupportThread thread;

    @Column(name = "sender_user_id", nullable = false, updatable = false)
    private UUID senderUserId;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Column(name = "attachment_key")
    private String attachmentKey;

    @Column(name = "read_at")
    private Instant readAt;
}

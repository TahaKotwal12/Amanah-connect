package com.amanahconnect.support;

import com.amanahconnect.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
public class SupportMessage extends TenantEntity {

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

    @Column(name = "seq", nullable = false, updatable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_side", nullable = false, updatable = false, length = 10)
    private SupportSide senderSide;

    @Column(name = "attachment_name", length = 200)
    private String attachmentName;

    @Column(name = "attachment_content_type", length = 100)
    private String attachmentContentType;

    @Column(name = "attachment_size")
    private Long attachmentSize;
}

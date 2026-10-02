package com.amanahconnect.notification;

import com.amanahconnect.common.persistence.BaseEntity;
import com.amanahconnect.common.persistence.CitextJdbcType;
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
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Transactional outbox: rows are written with the business change, a job sends them via SES. */
@Entity
@Table(name = "email_outbox")
@Getter
@Setter
public class EmailOutbox extends BaseEntity {

    /** Null for platform emails such as password resets. */
    @Column(name = "community_id", updatable = false)
    private UUID communityId;

    @JdbcType(CitextJdbcType.class)
    @Column(name = "to_email", nullable = false, columnDefinition = "citext")
    private String toEmail;

    @Column(name = "template", nullable = false, length = 100)
    private String template;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private EmailStatus status = EmailStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "ses_message_id", length = 100)
    private String sesMessageId;

    @Column(name = "error")
    private String error;

    @Column(name = "sent_at")
    private Instant sentAt;
}

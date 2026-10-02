package com.amanahconnect.support;

import com.amanahconnect.common.Priority;
import com.amanahconnect.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A helpdesk conversation between a community admin and the platform's super admins. */
@Entity
@Table(name = "support_threads")
@Getter
@Setter
public class SupportThread extends TenantEntity {

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ThreadStatus status = ThreadStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private Priority priority = Priority.MEDIUM;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt = Instant.now();
}

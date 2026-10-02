package com.amanahconnect.member;

import com.amanahconnect.common.persistence.BaseEntity;
import com.amanahconnect.common.persistence.CitextJdbcType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

/** A public self-registration waiting for an admin to approve or reject it. */
@Entity
@Table(name = "member_registrations")
@Getter
@Setter
public class MemberRegistration extends BaseEntity {

    @Column(name = "community_id", nullable = false, updatable = false)
    private UUID communityId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invite_id", nullable = false, updatable = false)
    private MemberInvite invite;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @JdbcType(CitextJdbcType.class)
    @Column(name = "email", columnDefinition = "citext")
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "group_label", length = 100)
    private String groupLabel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_fields", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> customFields = new HashMap<>();

    @Column(name = "consent_email", nullable = false)
    private boolean consentEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RegistrationStatus status = RegistrationStatus.PENDING;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;
}

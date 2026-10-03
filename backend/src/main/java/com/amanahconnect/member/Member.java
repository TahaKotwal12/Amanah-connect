package com.amanahconnect.member;

import com.amanahconnect.tenant.TenantEntity;
import com.amanahconnect.common.persistence.CitextJdbcType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "members")
@Getter
@Setter
public class Member extends TenantEntity {

    @Column(name = "member_no", nullable = false, length = 30)
    private String memberNo;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @JdbcType(CitextJdbcType.class)
    @Column(name = "email", columnDefinition = "citext")
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "group_label", length = 100)
    private String groupLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MemberStatus status = MemberStatus.ACTIVE;

    @Column(name = "joined_on")
    private LocalDate joinedOn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_fields", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> customFields = new HashMap<>();

    @Column(name = "consent_email", nullable = false)
    private boolean consentEmail;

    /** Soft delete: members stay referenced by invoices and payments. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Why the member was last deactivated or reactivated. */
    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "delete_reason")
    private String deleteReason;

    @Column(name = "deleted_by")
    private java.util.UUID deletedBy;
}

package com.amanahconnect.community;

import com.amanahconnect.auth.User;
import com.amanahconnect.common.persistence.BaseEntity;
import com.amanahconnect.common.persistence.CitextJdbcType;
import com.amanahconnect.plan.Plan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import com.amanahconnect.tenant.TenantFilters;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ParamDef;
import org.hibernate.type.SqlTypes;

/** A tenant. Its own id is the community id every tenant-owned row points at. */
@Entity
@Table(name = "communities")
@FilterDef(
        name = TenantFilters.COMMUNITY_FILTER,
        parameters = @ParamDef(name = TenantFilters.PARAMETER, type = UUID.class))
@Filter(name = TenantFilters.COMMUNITY_FILTER, condition = "id = :communityId")
@Getter
@Setter
public class Community extends BaseEntity {

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "slug", nullable = false, length = 80)
    private String slug;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private User owner;

    @Column(name = "contact_name", length = 150)
    private String contactName;

    @JdbcType(CitextJdbcType.class)
    @Column(name = "contact_email", columnDefinition = "citext")
    private String contactEmail;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    @Column(name = "address_line1", length = 200)
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    private String addressLine2;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "country", nullable = false, length = 2)
    private String country = "IN";

    @Column(name = "date_of_establishment")
    private LocalDate dateOfEstablishment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommunityStatus status = CommunityStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "upi_id", length = 100)
    private String upiId;

    @Column(name = "upi_payee_name", length = 150)
    private String upiPayeeName;

    @Column(name = "logo_key")
    private String logoKey;

    @Column(name = "financial_year_start_month", nullable = false)
    private short financialYearStartMonth = 4;

    /** Why the status last changed (suspension or archive reason). */
    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "status_changed_at")
    private java.time.Instant statusChangedAt;

    @Column(name = "status_changed_by")
    private UUID statusChangedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings = new HashMap<>();
}

package com.amanahconnect.billing;

import com.amanahconnect.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "fee_plans")
@Getter
@Setter
public class FeePlan extends BaseEntity {

    @Column(name = "community_id", nullable = false, updatable = false)
    private UUID communityId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private FeeKind kind;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private FeeFrequency frequency;

    @Column(name = "due_day")
    private Short dueDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to", nullable = false, length = 20)
    private FeeAudience appliesTo = FeeAudience.ALL_ACTIVE;

    /** {"group": "..."} for GROUP, {"memberIds": [...]} for SELECTED. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applies_to_filter", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> appliesToFilter = new HashMap<>();

    @Column(name = "active", nullable = false)
    private boolean active = true;
}

package com.amanahconnect.billing;

import com.amanahconnect.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "fee_plans")
@Getter
@Setter
public class FeePlan extends TenantEntity {

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

    /** Bill automatically every period (the daily job). Off by default: nobody is billed without being asked. */
    @Column(name = "auto_generate", nullable = false)
    private boolean autoGenerate;

    /** The period the daily job last billed, e.g. {@code 2026-10}. */
    @Column(name = "last_generated_period", length = 30)
    private String lastGeneratedPeriod;
}

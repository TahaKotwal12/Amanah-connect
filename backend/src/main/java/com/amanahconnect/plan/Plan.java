package com.amanahconnect.plan;

import com.amanahconnect.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "plans")
@Getter
@Setter
public class Plan extends BaseEntity {

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "price_monthly", nullable = false, precision = 14, scale = 2)
    private BigDecimal priceMonthly = BigDecimal.ZERO;

    @Column(name = "price_yearly", nullable = false, precision = 14, scale = 2)
    private BigDecimal priceYearly = BigDecimal.ZERO;

    /** {max_members, storage_mb, emails_per_month}; a null value means unlimited. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "limits", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> limits = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> features = new HashMap<>();

    @Column(name = "is_public", nullable = false)
    private boolean publicPlan = true;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}

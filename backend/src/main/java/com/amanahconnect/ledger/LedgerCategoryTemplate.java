package com.amanahconnect.ledger;

import com.amanahconnect.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Platform-wide default category, copied into each community when it is created. Not tenant data. */
@Entity
@Table(name = "ledger_category_templates")
@Getter
@Setter
public class LedgerCategoryTemplate extends BaseEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private LedgerType type;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}

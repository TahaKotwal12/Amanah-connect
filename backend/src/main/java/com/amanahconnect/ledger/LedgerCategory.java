package com.amanahconnect.ledger;

import com.amanahconnect.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ledger_categories")
@Getter
@Setter
public class LedgerCategory extends TenantEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 10)
    private LedgerType type;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}

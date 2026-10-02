package com.amanahconnect.billing;

import com.amanahconnect.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Last number issued per community, document type and financial year. Locked with FOR UPDATE. */
@Entity
@Table(name = "document_counters")
@Getter
@Setter
public class DocumentCounter extends BaseEntity {

    @Column(name = "community_id", nullable = false, updatable = false)
    private UUID communityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "counter_type", nullable = false, updatable = false, length = 20)
    private CounterType counterType;

    @Column(name = "financial_year", nullable = false, updatable = false, length = 9)
    private String financialYear;

    @Column(name = "last_value", nullable = false)
    private long lastValue;
}

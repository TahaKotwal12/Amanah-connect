package com.amanahconnect.ledger;

import com.amanahconnect.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * An income or expense. Corrections are reversal rows (negative amount, {@code reversedOf}, reason),
 * never deletes.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
public class LedgerEntry extends TenantEntity {

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 10)
    private LedgerType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private LedgerCategory category;

    @Column(name = "amount", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "notes")
    private String notes;

    @Column(name = "attachment_key")
    private String attachmentKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 10)
    private LedgerSource source = LedgerSource.MANUAL;

    @Column(name = "source_id", updatable = false)
    private UUID sourceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversed_of", updatable = false)
    private LedgerEntry reversedOf;

    @Column(name = "reversal_reason", updatable = false)
    private String reversalReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;
}

package com.amanahconnect.billing;

import com.amanahconnect.tenant.TenantEntity;
import com.amanahconnect.member.Member;
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
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "invoices")
@Getter
@Setter
public class Invoice extends TenantEntity {

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, updatable = false)
    private Member member;

    /** Assigned from {@link NumberingService} when the invoice is issued; drafts have none. */
    @Column(name = "invoice_no", length = 40)
    private String invoiceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private FeeKind kind;

    @Column(name = "period", length = 30)
    private String period;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "amount_paid", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fee_plan_id")
    private FeePlan feePlan;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn = LocalDate.now();

    /** What a manual invoice is for; generated invoices use the fee plan's name. */
    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "cancelled_at")
    private java.time.Instant cancelledAt;

    @Column(name = "cancelled_by")
    private java.util.UUID cancelledBy;
}

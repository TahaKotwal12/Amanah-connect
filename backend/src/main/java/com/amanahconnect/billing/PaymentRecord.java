package com.amanahconnect.billing;

import com.amanahconnect.common.persistence.BaseEntity;
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
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * A payment, or a reversal of one. A reversal is a new row with a negative amount, {@code reversedOf}
 * pointing at the original and a reason; payments are never edited away or deleted.
 */
@Entity
@Table(name = "payment_records")
@Getter
@Setter
public class PaymentRecord extends BaseEntity {

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "community_id", nullable = false, updatable = false)
    private UUID communityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", updatable = false)
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, updatable = false)
    private Member member;

    @Column(name = "amount", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private PaymentMethod method;

    @Column(name = "reference", length = 100)
    private String reference;

    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id")
    private Receipt receipt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversed_of", updatable = false)
    private PaymentRecord reversedOf;

    @Column(name = "reversal_reason", updatable = false)
    private String reversalReason;
}

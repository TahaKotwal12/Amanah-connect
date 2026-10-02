package com.amanahconnect.plan;

import com.amanahconnect.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** What a community pays Amanah Connect (not member dues). */
@Entity
@Table(name = "platform_subscriptions")
@Getter
@Setter
public class PlatformSubscription extends TenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "reference", length = 100)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    /** The day the community paid; platform revenue is reported by this date. */
    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "reminder_7d_sent_at")
    private java.time.Instant reminder7dSentAt;

    @Column(name = "reminder_1d_sent_at")
    private java.time.Instant reminder1dSentAt;

    @Column(name = "expired_notified_at")
    private java.time.Instant expiredNotifiedAt;
}

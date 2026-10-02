package com.amanahconnect.billing;

import com.amanahconnect.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "receipts")
@Getter
@Setter
public class Receipt extends BaseEntity {

    @Column(name = "community_id", nullable = false, updatable = false)
    private UUID communityId;

    /** From {@link NumberingService}, gap-free per community per financial year. */
    @Column(name = "receipt_no", nullable = false, updatable = false, length = 40)
    private String receiptNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_record_id", nullable = false, updatable = false)
    private PaymentRecord paymentRecord;

    @Column(name = "pdf_key")
    private String pdfKey;

    @Column(name = "emailed_at")
    private Instant emailedAt;
}

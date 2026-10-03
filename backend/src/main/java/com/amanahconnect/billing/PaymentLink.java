package com.amanahconnect.billing;

import com.amanahconnect.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A token that opens the public payment-info page of one invoice. Only the token's SHA-256 is stored. */
@Entity
@Table(name = "payment_links")
@Getter
@Setter
public class PaymentLink extends TenantEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;
}

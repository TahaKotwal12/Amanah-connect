package com.amanahconnect.billing;

import com.amanahconnect.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;

public interface PaymentRecordRepository extends TenantRepository<PaymentRecord, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<PaymentRecord> findWithLockByIdAndCommunityId(UUID id, UUID communityId);

    java.util.Optional<PaymentRecord> findByCommunityIdAndIdempotencyKey(UUID communityId, String idempotencyKey);

    boolean existsByCommunityIdAndReversedOfId(UUID communityId, UUID reversedOfId);

    java.util.Optional<PaymentRecord> findByCommunityIdAndReversedOfId(UUID communityId, UUID reversedOfId);

    /** Everything paid against an invoice, net of reversals (a reversal row is negative). */
    @org.springframework.data.jpa.repository.Query("select coalesce(sum(p.amount), 0) from PaymentRecord p where p.communityId = :communityId and p.invoice.id = :invoiceId")
    java.math.BigDecimal sumAmountByCommunityIdAndInvoiceId(@org.springframework.data.repository.query.Param("communityId") UUID communityId, @org.springframework.data.repository.query.Param("invoiceId") UUID invoiceId);


    List<PaymentRecord> findByCommunityIdAndInvoiceId(UUID communityId, UUID invoiceId);
}

package com.amanahconnect.billing;

import com.amanahconnect.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends TenantRepository<Receipt, UUID> {

    Optional<Receipt> findByCommunityIdAndReceiptNo(UUID communityId, String receiptNo);

    Optional<Receipt> findByCommunityIdAndPaymentRecordId(UUID communityId, UUID paymentRecordId);
}

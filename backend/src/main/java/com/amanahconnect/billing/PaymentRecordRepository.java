package com.amanahconnect.billing;

import com.amanahconnect.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;

public interface PaymentRecordRepository extends TenantRepository<PaymentRecord, UUID> {

    List<PaymentRecord> findByCommunityIdAndInvoiceId(UUID communityId, UUID invoiceId);
}

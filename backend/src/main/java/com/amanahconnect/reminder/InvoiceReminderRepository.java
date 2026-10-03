package com.amanahconnect.reminder;

import com.amanahconnect.tenant.TenantRepository;
import java.util.UUID;

public interface InvoiceReminderRepository extends TenantRepository<InvoiceReminder, UUID> {

    long countByCommunityIdAndInvoiceIdAndKind(UUID communityId, UUID invoiceId, ReminderKind kind);
}

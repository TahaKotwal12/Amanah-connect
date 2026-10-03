package com.amanahconnect.billing;

import com.amanahconnect.tenant.TenantRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InvoiceRepository extends TenantRepository<Invoice, UUID> {

    /** Row-locked: concurrent payments, reversals and cancellations of one invoice queue here. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<Invoice> findWithLockByIdAndCommunityId(UUID id, UUID communityId);

    boolean existsByCommunityIdAndFeePlanId(UUID communityId, UUID feePlanId);


    Optional<Invoice> findByCommunityIdAndInvoiceNo(UUID communityId, String invoiceNo);

    Page<Invoice> findByCommunityIdAndStatus(UUID communityId, InvoiceStatus status, Pageable pageable);

    List<Invoice> findByCommunityIdAndStatusInAndDueDateBefore(
            UUID communityId, Collection<InvoiceStatus> statuses, LocalDate dueDate);
}

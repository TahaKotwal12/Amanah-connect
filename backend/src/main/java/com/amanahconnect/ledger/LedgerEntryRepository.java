package com.amanahconnect.ledger;

import com.amanahconnect.tenant.TenantRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface LedgerEntryRepository extends TenantRepository<LedgerEntry, UUID> {

    /** The ledger entry a payment (or a reversal of one) produced. */
    java.util.Optional<LedgerEntry> findByCommunityIdAndSourceAndSourceId(UUID communityId, LedgerSource source, UUID sourceId);

    boolean existsByCommunityIdAndReversedOfId(UUID communityId, UUID reversedOfId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<LedgerEntry> findWithLockByIdAndCommunityId(UUID id, UUID communityId);

    long countByCommunityIdAndCategoryId(UUID communityId, UUID categoryId);


    Page<LedgerEntry> findByCommunityIdAndEntryDateBetween(
            UUID communityId, LocalDate from, LocalDate to, Pageable pageable);
}

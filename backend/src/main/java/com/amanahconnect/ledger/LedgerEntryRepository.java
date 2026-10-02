package com.amanahconnect.ledger;

import com.amanahconnect.tenant.TenantRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface LedgerEntryRepository extends TenantRepository<LedgerEntry, UUID> {

    Page<LedgerEntry> findByCommunityIdAndEntryDateBetween(
            UUID communityId, LocalDate from, LocalDate to, Pageable pageable);
}

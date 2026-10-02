package com.amanahconnect.ledger;

import com.amanahconnect.tenant.TenantRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerCategoryRepository extends TenantRepository<LedgerCategory, UUID> {

    List<LedgerCategory> findByCommunityIdAndActiveTrueOrderByNameAsc(UUID communityId);

    Optional<LedgerCategory> findByCommunityIdAndNameAndType(UUID communityId, String name, LedgerType type);
}

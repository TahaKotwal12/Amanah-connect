package com.amanahconnect.community.imports;

import com.amanahconnect.tenant.TenantRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;

public interface ImportBatchRepository extends TenantRepository<ImportBatch, UUID> {

    Optional<ImportBatch> findByCommunityIdAndBatchId(UUID communityId, UUID batchId);

    /** Row-locks the batch so two concurrent confirms are serialised and the second sees CONFIRMED. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ImportBatch> findWithLockByCommunityIdAndBatchId(UUID communityId, UUID batchId);
}

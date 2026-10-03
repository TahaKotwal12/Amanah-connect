package com.amanahconnect.complaint;

import com.amanahconnect.tenant.TenantRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ComplaintRepository extends TenantRepository<Complaint, UUID> {

    Page<Complaint> findByCommunityIdAndStatus(UUID communityId, ComplaintStatus status, Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<Complaint> findWithLockByIdAndCommunityId(UUID id, UUID communityId);
}

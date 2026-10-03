package com.amanahconnect.support;

import com.amanahconnect.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SupportThreadRepository extends TenantRepository<SupportThread, UUID> {

    Page<SupportThread> findByCommunityIdAndStatus(UUID communityId, ThreadStatus status, Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<SupportThread> findWithLockByIdAndCommunityId(UUID id, UUID communityId);

    /** The platform's super admins see every community's threads. */
    @com.amanahconnect.tenant.CrossTenantLookup("super admin helpdesk: threads of every community")
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from SupportThread t where t.id = :id")
    Optional<SupportThread> findWithLockForPlatform(@org.springframework.data.repository.query.Param("id") UUID id);
}

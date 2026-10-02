package com.amanahconnect.support;

import com.amanahconnect.tenant.TenantRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SupportThreadRepository extends TenantRepository<SupportThread, UUID> {

    Page<SupportThread> findByCommunityIdAndStatus(UUID communityId, ThreadStatus status, Pageable pageable);
}

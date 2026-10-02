package com.amanahconnect.support;

import com.amanahconnect.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;

public interface SupportMessageRepository extends TenantRepository<SupportMessage, UUID> {

    List<SupportMessage> findByCommunityIdAndThreadIdOrderByCreatedAtAsc(UUID communityId, UUID threadId);
}

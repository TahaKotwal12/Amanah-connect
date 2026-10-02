package com.amanahconnect.notification;

import com.amanahconnect.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;

public interface NotificationSettingsRepository extends TenantRepository<NotificationSettings, UUID> {

    Optional<NotificationSettings> findByCommunityId(UUID communityId);
}

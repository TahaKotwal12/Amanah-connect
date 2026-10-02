package com.amanahconnect.tenant;

import java.util.Optional;
import java.util.UUID;

/** Finds the community a user administers. Implemented next to the community data it reads. */
public interface TenantResolver {

    Optional<CurrentTenant> resolve(UUID userId);
}

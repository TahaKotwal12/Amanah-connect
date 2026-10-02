package com.amanahconnect.tenant;

import java.util.UUID;

/**
 * The community the authenticated COMMUNITY_ADMIN is acting for. Built from the principal only, never
 * from request input.
 *
 * @param status the community status name (PENDING, ACTIVE, SUSPENDED, ARCHIVED)
 * @param writable false for SUSPENDED and ARCHIVED communities, which may only read
 */
public record CurrentTenant(UUID communityId, UUID userId, String status, boolean writable) {}

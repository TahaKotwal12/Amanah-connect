package com.amanahconnect.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuditDtos {

    private AuditDtos() {}

    /** What can be asked for. Every field is optional; they combine with AND. */
    public record Filter(UUID actor, UUID communityId, String action, String actionPrefix, String entityType, UUID entityId, Instant from, Instant toExclusive) {}

    /** The platform's view: everything, with where it came from. */
    public record AdminEntry(
            UUID id, Instant at, String action, String summary, UUID actorId, String actorName, String actorRole, UUID communityId, String communityName,
            String entityType, UUID entityId, Map<String, Object> before, Map<String, Object> after, String ip, String userAgent, String requestId) {}

    /** A community's view of its own trail: no addresses, devices or request ids; platform staff appear as such. */
    public record CommunityEntry(
            UUID id, Instant at, String action, String summary, String actorName, boolean byPlatformStaff, String entityType, UUID entityId,
            Map<String, Object> before, Map<String, Object> after) {}

    /** {@code nextCursor} is null on the last page; pass it back as {@code cursor} for the next one. */
    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {}
}

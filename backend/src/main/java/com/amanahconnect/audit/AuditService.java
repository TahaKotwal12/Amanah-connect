package com.amanahconnect.audit;

import com.amanahconnect.common.web.RequestInfo;
import com.amanahconnect.tenant.TenantContext;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends audit records. Joins the caller's transaction, so a business change and its audit entry
 * commit or roll back together.
 *
 * <p>{@code before}/{@code after} may be any object (a DTO, a map, even an entity): they are converted
 * and passed through {@link AuditRedactor}, which removes password hashes, tokens, TOTP secrets,
 * recovery codes and anything that looks like one, at any depth, before anything is stored.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final AuditRedactor redactor;

    public AuditService(AuditLogRepository repository, AuditRedactor redactor) {
        this.repository = repository;
        this.redactor = redactor;
    }

    /**
     * Records an action by the authenticated user for the current community. The actor comes from the
     * security context, the community from the tenant context, and the client address, user agent and
     * request id from the current request.
     */
    @Transactional
    public void record(String action, String entityType, UUID entityId, Object before, Object after) {
        record(
                action,
                currentActor(),
                TenantContext.current().map(tenant -> tenant.communityId()).orElse(null),
                entityType,
                entityId,
                before,
                after);
    }

    /** Records an action with an explicit actor and community (auth events, super admin and system actions). */
    @Transactional
    public void record(
            String action,
            UUID actorUserId,
            UUID communityId,
            String entityType,
            UUID entityId,
            Object before,
            Object after) {
        RequestInfo info = RequestInfo.current();
        AuditLog entry = new AuditLog();
        entry.setAction(action);
        entry.setActorUserId(actorUserId);
        entry.setCommunityId(communityId);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setBefore(redactor.redactToMap(before));
        entry.setAfter(redactor.redactToMap(after));
        entry.setIp(info.ip());
        entry.setUserAgent(info.userAgent());
        entry.setRequestId(info.requestId());
        repository.save(entry);
    }

    private static UUID currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            try {
                return UUID.fromString(token.getToken().getSubject());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

}

package com.amanahconnect.audit;

import com.amanahconnect.common.web.RequestInfo;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends audit records. Joins the caller's transaction, so a business change and its audit entry
 * commit or roll back together.
 *
 * <p>Callers must pass only safe values in {@code before}/{@code after}: never password hashes,
 * tokens, TOTP secrets or recovery codes. (The shared redaction layer arrives with the common-layer
 * work; until then auth passes identifiers and flags only.)
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(
            String action,
            UUID actorUserId,
            UUID communityId,
            String entityType,
            UUID entityId,
            Map<String, Object> before,
            Map<String, Object> after) {
        RequestInfo info = RequestInfo.current();
        AuditLog entry = new AuditLog();
        entry.setAction(action);
        entry.setActorUserId(actorUserId);
        entry.setCommunityId(communityId);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setBefore(before);
        entry.setAfter(after);
        entry.setIp(info.ip());
        entry.setUserAgent(info.userAgent());
        entry.setRequestId(info.requestId());
        repository.save(entry);
    }
}

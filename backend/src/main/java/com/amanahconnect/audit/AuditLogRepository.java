package com.amanahconnect.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Append and read only. There is deliberately no update or delete method. */
public interface AuditLogRepository extends Repository<AuditLog, UUID> {

    <S extends AuditLog> S save(S entry);

    Page<AuditLog> findByCommunityId(UUID communityId, Pageable pageable);

    Page<AuditLog> findByEntityTypeAndEntityId(String entityType, UUID entityId, Pageable pageable);
}

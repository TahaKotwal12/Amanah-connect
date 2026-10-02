package com.amanahconnect.tenant;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Switches the Hibernate tenant filters on and off for the current transaction.
 *
 * <p>Two filters exist, both parameterised with the community id: {@link #TENANT_FILTER} adds
 * {@code community_id = :communityId} to every query on a tenant entity, and {@link #COMMUNITY_FILTER}
 * restricts the community table to the caller's own row.
 *
 * <p><b>This is a second safety net, not the primary control.</b> Repositories still take the community
 * id explicitly. Hibernate applies filters to HQL/JPQL, criteria and derived queries; it does NOT apply
 * them to {@code EntityManager.find}, to loading a lazy association by id, or to native SQL.
 */
public final class TenantFilters {

    public static final String TENANT_FILTER = "tenantFilter";
    public static final String COMMUNITY_FILTER = "communityIdFilter";
    public static final String PARAMETER = "communityId";

    private TenantFilters() {}

    public static void enable(EntityManager entityManager, UUID communityId) {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter(TENANT_FILTER).setParameter(PARAMETER, communityId);
        session.enableFilter(COMMUNITY_FILTER).setParameter(PARAMETER, communityId);
    }

    public static void disable(EntityManager entityManager) {
        Session session = entityManager.unwrap(Session.class);
        session.disableFilter(TENANT_FILTER);
        session.disableFilter(COMMUNITY_FILTER);
    }

    /** Applies the tenant (or, for null, no tenant) to every EntityManager bound to the current thread. */
    static void applyToActiveTransactions(UUID communityIdOrNull) {
        for (Object resource : TransactionSynchronizationManager.getResourceMap().values()) {
            if (resource instanceof EntityManagerHolder holder && holder.getEntityManager().isOpen()) {
                if (communityIdOrNull == null) {
                    disable(holder.getEntityManager());
                } else {
                    enable(holder.getEntityManager(), communityIdOrNull);
                }
            }
        }
    }
}

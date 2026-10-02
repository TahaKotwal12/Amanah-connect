package com.amanahconnect.tenant;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * A JPA transaction manager that turns the tenant filters on as each transaction begins, whenever a
 * {@link TenantContext} is bound. Every repository call runs in a transaction, so every query made on
 * behalf of a community admin is filtered without any call site having to remember to do it.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public TenantAwareJpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        TenantContext.current().ifPresent(tenant -> TenantFilters.applyToActiveTransactions(tenant.communityId()));
    }
}

package com.amanahconnect.tenant;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The tenant of the current thread. Set by {@link TenantContextFilter} for the duration of a
 * /community/** request from the authenticated principal, and cleared afterwards. Code that works for
 * a community outside a request (scheduled jobs, public flows resolved from a token) uses
 * {@link #callAs} so the Hibernate filter applies there too.
 */
public final class TenantContext {

    private static final ThreadLocal<CurrentTenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static Optional<CurrentTenant> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** @throws ApiException 403 if no tenant is bound (a /community code path reached without one) */
    public static CurrentTenant require() {
        CurrentTenant tenant = CURRENT.get();
        if (tenant == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "No community is associated with this request.");
        }
        return tenant;
    }

    /** Runs the action as the given tenant and restores the previous one (or none) afterwards. */
    public static <T> T callAs(CurrentTenant tenant, Supplier<T> action) {
        CurrentTenant previous = CURRENT.get();
        bind(tenant);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                unbind();
            } else {
                bind(previous);
            }
        }
    }

    public static void runAs(CurrentTenant tenant, Runnable action) {
        callAs(
                tenant,
                () -> {
                    action.run();
                    return null;
                });
    }

    /** Binds the tenant and switches the Hibernate filters on for any transaction already running. */
    static void bind(CurrentTenant tenant) {
        CURRENT.set(tenant);
        TenantFilters.applyToActiveTransactions(tenant.communityId());
    }

    static void unbind() {
        CURRENT.remove();
        TenantFilters.applyToActiveTransactions(null);
    }
}

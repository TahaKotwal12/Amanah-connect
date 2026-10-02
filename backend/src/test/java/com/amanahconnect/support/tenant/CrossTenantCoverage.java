package com.amanahconnect.support.tenant;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which controller methods have been exercised by the cross-tenant harness, so
 * {@code TenantCoverageIT} can fail the build if a /community endpoint has no cross-tenant test.
 */
public final class CrossTenantCoverage {

    private static final Set<String> COVERED = ConcurrentHashMap.newKeySet();

    private CrossTenantCoverage() {}

    public static void markCovered(String handlerKey) {
        COVERED.add(handlerKey);
    }

    public static Set<String> covered() {
        return Set.copyOf(COVERED);
    }
}

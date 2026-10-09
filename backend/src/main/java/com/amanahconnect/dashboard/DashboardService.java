package com.amanahconnect.dashboard;

import com.amanahconnect.dashboard.DashboardDtos.Dashboard;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The community dashboard, worked out at most once per {@code app.dashboard.cache-seconds} (30) per community: a busy admin refreshing the page, or
 * ten admins of one community opening it together, cost one set of queries. The cache is per community and in memory; nothing about one
 * community can be served to another, because the key is the community id taken from the caller's principal.
 */
@Service
public class DashboardService {

    private final DashboardQueries queries;
    private final Cache<UUID, Dashboard> cache;

    public DashboardService(DashboardQueries queries, DashboardProperties properties) {
        this.queries = queries;
        this.cache = Caffeine.newBuilder().maximumSize(5_000).expireAfterWrite(Duration.ofSeconds(Math.max(properties.cacheSeconds(), 0))).build();
    }

    @Transactional(readOnly = true)
    public Dashboard get(UUID communityId) {
        return cache.get(communityId, queries::build);
    }

    /** Forgets what is held (tests; and anything that must show a change at once). */
    public void evict(UUID communityId) {
        cache.invalidate(communityId);
    }

    public void evictAll() {
        cache.invalidateAll();
    }
}

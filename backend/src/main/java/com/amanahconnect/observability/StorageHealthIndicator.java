package com.amanahconnect.observability;

import com.amanahconnect.file.ObjectStorage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Optional readiness check for object storage (only checks when {@code app.health.storage-enabled=true}; otherwise always UP). Off by default: file storage is a side feature,
 * and taking the whole API out of the load balancer because S3 hiccuped would be worse than answering "storage unavailable" on the few
 * endpoints that need it. Turn it on when you would rather fail over than serve without files. A missing key is a healthy answer.
 */
@Component("storage")
public class StorageHealthIndicator implements HealthIndicator {

    private final ObjectStorage storage;
    private final boolean enabled;

    public StorageHealthIndicator(ObjectStorage storage, @Value("${app.health.storage-enabled:false}") boolean enabled) {
        this.storage = storage;
        this.enabled = enabled;
    }

    @Override
    public Health health() {
        if (!enabled) return Health.up().build();
        try {
            CompletableFuture.runAsync(() -> storage.head("health/probe")).get(3, TimeUnit.SECONDS);
            return Health.up().build();
        } catch (Exception e) {
            return Health.down().build(); // details are never shown, and the cause is not logged at every probe
        }
    }
}

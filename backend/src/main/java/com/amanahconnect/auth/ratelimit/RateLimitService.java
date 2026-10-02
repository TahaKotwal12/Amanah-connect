package com.amanahconnect.auth.ratelimit;

import com.amanahconnect.common.error.RateLimitedException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * In-memory token buckets (Bucket4j), one per key. A single API instance is assumed (see the
 * blueprint's honest limits); a shared store would be needed to scale out.
 *
 * <p>Keys can be attacker-chosen (an email or IP per request), so the map is size-bounded and evicts
 * the least recently used bucket instead of growing without limit.
 */
@Service
public class RateLimitService {

    private static final int MAX_BUCKETS = 50_000;

    private final Map<String, Bucket> buckets =
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                    return size() > MAX_BUCKETS;
                }
            };

    /**
     * Takes one token from the bucket for {@code key}.
     *
     * @throws RateLimitedException when the bucket is empty, carrying the Retry-After value
     */
    public void consume(String key, int capacity, Duration window) {
        Bucket bucket;
        synchronized (buckets) {
            bucket =
                    buckets.computeIfAbsent(
                            key + "|" + capacity + "|" + window.toSeconds(),
                            k ->
                                    Bucket.builder()
                                            .addLimit(limit -> limit.capacity(capacity).refillIntervally(capacity, window))
                                            .build());
        }
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long seconds = (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000d);
            throw new RateLimitedException(seconds);
        }
    }
}

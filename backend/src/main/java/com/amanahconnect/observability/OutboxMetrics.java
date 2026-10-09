package com.amanahconnect.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Email outbox depth as gauges: waiting, failed in the last 24 hours, and the age of the oldest waiting mail (the number to alert on).
 * Each value is read from the database at most every 15 seconds, however often Prometheus scrapes.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, JdbcTemplate jdbc, @org.springframework.beans.factory.annotation.Value("${app.observability.gauge-cache-seconds:15}") long cacheSeconds) {
        long cacheNanos = Duration.ofSeconds(cacheSeconds).toNanos();
        register(registry, cacheNanos, "amanah.outbox.pending", "Mail waiting to be sent", () -> count(jdbc, "SELECT count(*) FROM email_outbox WHERE status = 'PENDING'"));
        register(registry, cacheNanos, "amanah.outbox.failed.24h", "Mail that failed for good in the last 24 hours",
                () -> count(jdbc, "SELECT count(*) FROM email_outbox WHERE status = 'FAILED' AND updated_at > now() - interval '24 hours'"));
        register(registry, cacheNanos, "amanah.outbox.oldest.pending.seconds", "Age of the oldest waiting mail",
                () -> count(jdbc, "SELECT coalesce(extract(epoch FROM now() - min(next_attempt_at)), 0)::bigint FROM email_outbox WHERE status = 'PENDING' AND next_attempt_at < now()"));
    }

    private static void register(MeterRegistry registry, long cacheNanos, String name, String description, Supplier<Long> query) {
        AtomicLong value = new AtomicLong();
        AtomicLong readAt = new AtomicLong(System.nanoTime() - 2 * cacheNanos - 1);
        Gauge.builder(name, () -> {
                    long now = System.nanoTime();
                    if (now - readAt.get() >= cacheNanos) {
                        readAt.set(now);
                        try {
                            value.set(query.get());
                        } catch (RuntimeException e) {
                            // a scrape must never fail because the database is busy; keep the last value
                        }
                    }
                    return value.get();
                })
                .description(description)
                .register(registry);
    }

    private static long count(JdbcTemplate jdbc, String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}

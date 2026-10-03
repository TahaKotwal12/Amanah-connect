package com.amanahconnect.complaint;

import com.amanahconnect.common.Priority;
import java.time.Duration;
import java.time.Instant;

/** How long a complaint may stay unresolved before it is flagged, by priority. The age is shown in whole days. */
public final class ComplaintSla {

    private ComplaintSla() {}

    public static int targetDays(Priority priority) {
        return switch (priority) {
            case URGENT -> 1;
            case HIGH -> 3;
            case MEDIUM -> 7;
            case LOW -> 14;
        };
    }

    /** Created to resolved (or closed) for finished complaints, created to now for active ones. */
    public static long ageDays(Instant createdAt, Instant resolvedAt, Instant closedAt, Instant now) {
        Instant end = resolvedAt != null ? resolvedAt : closedAt != null ? closedAt : now;
        return Math.max(0, Duration.between(createdAt, end).toDays());
    }

    /** Only complaints still being worked on can breach; one resolved late is history, not an alert. */
    public static boolean breached(ComplaintStatus status, Priority priority, Instant createdAt, Instant now) {
        if (status != ComplaintStatus.OPEN && status != ComplaintStatus.IN_PROGRESS) return false;
        return Duration.between(createdAt, now).compareTo(Duration.ofDays(targetDays(priority))) > 0;
    }
}

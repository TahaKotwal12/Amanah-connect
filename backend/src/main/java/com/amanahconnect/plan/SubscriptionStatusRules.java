package com.amanahconnect.plan;

import java.time.LocalDate;

/**
 * How a stored subscription is shown. The stored status is ACTIVE, EXPIRED or CANCELLED; whether an
 * ACTIVE one is "expiring" or has in fact lapsed depends on today's date, so it is derived, never stored.
 *
 * <ul>
 *   <li>CANCELLED: cancelled.</li>
 *   <li>EXPIRED: stored EXPIRED, or its period ended before today.</li>
 *   <li>EXPIRING: ends within the window and no later subscription has already renewed it.</li>
 *   <li>ACTIVE: otherwise.</li>
 * </ul>
 * {@link #DISPLAY_SQL} is the same rule for list queries and must stay in step with {@link #display}.
 */
public final class SubscriptionStatusRules {

    public static final String ACTIVE = "ACTIVE";
    public static final String EXPIRING = "EXPIRING";
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";

    /** For a row aliased {@code s}; needs the named parameters :today and :windowEnd. */
    public static final String DISPLAY_SQL =
            """
            CASE WHEN s.status = 'CANCELLED' THEN 'CANCELLED'
                 WHEN s.status = 'EXPIRED' OR s.period_end < :today THEN 'EXPIRED'
                 WHEN s.period_end <= :windowEnd AND NOT EXISTS (
                        SELECT 1 FROM platform_subscriptions n
                        WHERE n.community_id = s.community_id AND n.status = 'ACTIVE' AND n.period_end > s.period_end)
                      THEN 'EXPIRING'
                 ELSE 'ACTIVE' END""";

    private SubscriptionStatusRules() {}

    /** @param renewed whether a later ACTIVE subscription exists for the same community */
    public static String display(String storedStatus, LocalDate periodEnd, LocalDate today, int windowDays, boolean renewed) {
        if (CANCELLED.equals(storedStatus)) {
            return CANCELLED;
        }
        if (EXPIRED.equals(storedStatus) || periodEnd.isBefore(today)) {
            return EXPIRED;
        }
        if (!periodEnd.isAfter(today.plusDays(windowDays)) && !renewed) {
            return EXPIRING;
        }
        return ACTIVE;
    }
}

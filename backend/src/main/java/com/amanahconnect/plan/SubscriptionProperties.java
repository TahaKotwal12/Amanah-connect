package com.amanahconnect.plan;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Subscription policy ({@code app.subscriptions.*}).
 *
 * @param expiringWindowDays "EXPIRING" in lists and the default for the expiring endpoint
 * @param autoSuspendEnabled automatic suspension after the grace period. Off by default: the job only
 *     warns, and never suspends a community unless this is turned on deliberately.
 * @param graceDays days after expiry before an automatic suspension (only if enabled)
 */
@ConfigurationProperties(prefix = "app.subscriptions")
public record SubscriptionProperties(
        @DefaultValue("30") int expiringWindowDays,
        @DefaultValue("false") boolean autoSuspendEnabled,
        @DefaultValue("7") int graceDays) {}

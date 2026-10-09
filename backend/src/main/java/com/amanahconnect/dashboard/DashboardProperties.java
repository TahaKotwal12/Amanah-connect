package com.amanahconnect.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Dashboard settings ({@code app.dashboard.*}).
 *
 * @param cacheSeconds how long one community's dashboard is reused before it is worked out again (the figures are a few seconds stale at worst)
 * @param upcomingDays how far ahead "upcoming dues" looks
 */
@ConfigurationProperties(prefix = "app.dashboard")
public record DashboardProperties(@DefaultValue("30") int cacheSeconds, @DefaultValue("14") int upcomingDays) {}

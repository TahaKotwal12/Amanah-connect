package com.amanahconnect.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-level settings ({@code app.platform.*}).
 *
 * @param notificationEmail where super-admin notifications (new leads) go. When blank they go to every
 *     active SUPER_ADMIN account.
 */
@ConfigurationProperties(prefix = "app.platform")
public record PlatformProperties(String notificationEmail) {}

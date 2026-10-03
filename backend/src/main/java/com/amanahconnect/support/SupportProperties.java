package com.amanahconnect.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Helpdesk settings ({@code app.support.*}).
 *
 * @param emailReminderMinutes while a side has unread messages, it is emailed once when the first one arrives, and again
 *     only if this many minutes have passed since the last email (a burst of messages sends one email)
 */
@ConfigurationProperties(prefix = "app.support")
public record SupportProperties(@DefaultValue("30") int emailReminderMinutes) {}

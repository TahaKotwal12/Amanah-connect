package com.amanahconnect.auth.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Request limits ({@code app.rate-limit.*}), all in-memory per instance. */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("10") int loginPerIpPerMinute,
        @DefaultValue("5") int loginPerEmailPerMinute,
        @DefaultValue("10") int mfaPerIpPerMinute,
        @DefaultValue("5") int forgotPasswordPerIpPerHour,
        @DefaultValue("5") int leadPerIpPerHour,
        @DefaultValue("30") int inviteViewPerIpPerMinute,
        @DefaultValue("10") int inviteRegisterPerIpPerHour) {}

package com.amanahconnect.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Audit trail settings ({@code app.audit.*}).
 *
 * @param exportLimit most rows one CSV export contains (newest first); a larger match is cut off and the response says so
 */
@ConfigurationProperties(prefix = "app.audit")
public record AuditProperties(@DefaultValue("100000") int exportLimit) {}

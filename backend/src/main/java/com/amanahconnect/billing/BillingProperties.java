package com.amanahconnect.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Billing settings ({@code app.billing.*}).
 *
 * @param payLinkDays how long a payment link in a bill email stays valid
 * @param generationMaxMembers largest number of invoices one generate call creates (a guard, not a plan limit)
 */
@ConfigurationProperties(prefix = "app.billing")
public record BillingProperties(@DefaultValue("60") int payLinkDays, @DefaultValue("5000") int generationMaxMembers) {}

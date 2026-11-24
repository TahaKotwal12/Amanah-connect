package com.amanahconnect.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Billing settings ({@code app.billing.*}).
 *
 * @param payLinkDays how long a payment link in a bill email stays valid
 * @param generationMaxMembers largest number of invoices one generate call creates (a guard, not a plan limit)
 * @param upiEnabled UPI pay links and QR codes in bills and on the public page. Off in phase 1 (cash only); the code stays so it can be switched back on
 */
@ConfigurationProperties(prefix = "app.billing")
public record BillingProperties(
        @DefaultValue("60") int payLinkDays,
        @DefaultValue("5000") int generationMaxMembers,
        @DefaultValue("true") boolean upiEnabled) {}

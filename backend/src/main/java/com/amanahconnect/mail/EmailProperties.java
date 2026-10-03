package com.amanahconnect.mail;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Email engine settings ({@code app.email.*}).
 *
 * @param from the From address, e.g. {@code Amanah Connect <no-reply@your-domain>}; the domain must be verified in SES
 * @param maxAttempts a mail is marked FAILED after this many attempts
 * @param backoffBase delay after the first failed attempt; it doubles with every further one (1, 2, 4, 8, 16 minutes)
 * @param lease how long a mail being sent is reserved for the worker that took it; if that worker dies the mail comes back after this
 * @param batchSize mails taken per worker run
 * @param maxAttachmentBytes largest attachment (a receipt PDF) added to a mail
 * @param previewEnabled the super admin template preview endpoint exists only when this is on (local and staging, never production)
 * @param sesConfigurationSet optional SES configuration set, sent as a header so bounce and complaint events reach the SNS topic
 * @param sns where bounce and complaint notifications come from
 */
@ConfigurationProperties(prefix = "app.email")
public record EmailProperties(
        @DefaultValue("Amanah Connect <no-reply@amanahconnect.local>") String from,
        @DefaultValue("6") int maxAttempts,
        @DefaultValue("PT1M") Duration backoffBase,
        @DefaultValue("PT5M") Duration lease,
        @DefaultValue("50") int batchSize,
        @DefaultValue("3145728") long maxAttachmentBytes,
        @DefaultValue("false") boolean previewEnabled,
        @DefaultValue("") String sesConfigurationSet,
        Sns sns) {

    /**
     * @param topicArns only notifications from these SNS topics are accepted (empty = the webhook accepts nothing)
     * @param certHostPattern signing certificates are only fetched from hosts matching this regular expression
     */
    public record Sns(@DefaultValue("") List<String> topicArns, @DefaultValue("^sns\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?$") String certHostPattern) {}

    public EmailProperties {
        if (sns == null) sns = new Sns(List.of(), "^sns\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?$");
    }
}

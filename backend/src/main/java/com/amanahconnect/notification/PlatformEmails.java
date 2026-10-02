package com.amanahconnect.notification;

import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.auth.UserRole;
import com.amanahconnect.auth.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Queues emails sent by the platform itself (to community owners, leads and super admins) in the
 * transactional outbox. They carry no community id, so they never use a community's own email quota.
 * The sender job arrives with the email engine; until then rows wait as PENDING.
 *
 * <p>Payloads hold display values only. Never put a secret or token in one (reset and invitation links
 * are queued by {@code AuthEmails}, which documents why).
 */
@Component
public class PlatformEmails {

    public static final String COMMUNITY_SUSPENDED = "community-suspended";
    public static final String COMMUNITY_ACTIVATED = "community-activated";
    public static final String SUBSCRIPTION_EXPIRING = "subscription-expiring";
    public static final String SUBSCRIPTION_EXPIRED = "subscription-expired";
    public static final String LEAD_NOTIFICATION = "lead-notification";
    public static final String LEAD_ACKNOWLEDGEMENT = "lead-acknowledgement";

    private final EmailOutboxRepository outbox;
    private final UserRepository users;
    private final PlatformProperties properties;
    private final Clock clock;

    public PlatformEmails(EmailOutboxRepository outbox, UserRepository users, PlatformProperties properties, Clock clock) {
        this.outbox = outbox;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    public void toAddress(String template, String toEmail, Map<String, Object> payload) {
        EmailOutbox email = new EmailOutbox();
        email.setToEmail(toEmail);
        email.setTemplate(template);
        email.setPayload(new LinkedHashMap<>(payload));
        outbox.save(email);
    }

    /** Like {@link #toAddress} but skips the email if the same template went to the address recently. */
    public boolean toAddressOncePer(String template, String toEmail, Map<String, Object> payload, Duration window) {
        if (outbox.existsByToEmailAndTemplateAndCreatedAtGreaterThanEqual(toEmail, template, clock.instant().minus(window))) {
            return false;
        }
        toAddress(template, toEmail, payload);
        return true;
    }

    /** The configured notification address, or every active super admin. */
    public List<String> superAdminAddresses() {
        Set<String> addresses = new LinkedHashSet<>();
        String configured = properties.notificationEmail();
        if (configured != null && !configured.isBlank()) {
            addresses.add(configured.trim());
        } else {
            users.findByRoleAndStatus(UserRole.SUPER_ADMIN, UserStatus.ACTIVE).forEach(u -> addresses.add(u.getEmail()));
        }
        return List.copyOf(addresses);
    }

    public void toSuperAdmins(String template, Map<String, Object> payload) {
        superAdminAddresses().forEach(address -> toAddress(template, address, payload));
    }
}

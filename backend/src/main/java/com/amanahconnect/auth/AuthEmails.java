package com.amanahconnect.auth;

import com.amanahconnect.notification.EmailOutbox;
import com.amanahconnect.notification.EmailOutboxRepository;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Queues the account emails (reset link, invitation) in the transactional outbox, in the same
 * transaction as the token. The email sender job arrives later; until then rows simply wait as PENDING.
 *
 * <p>The raw token is inside the link and therefore in the outbox payload until the email is sent.
 * The sender must clear the payload once it is sent (tracked for the email-engine work).
 */
@Component
public class AuthEmails {

    public static final String TEMPLATE_PASSWORD_RESET = "password-reset";
    public static final String TEMPLATE_INVITATION = "invitation";

    private final EmailOutboxRepository outbox;
    private final AuthProperties properties;

    public AuthEmails(EmailOutboxRepository outbox, AuthProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    public void passwordReset(User user, String rawToken, Duration validFor) {
        enqueue(TEMPLATE_PASSWORD_RESET, user, "/reset-password", rawToken, validFor);
    }

    public void invitation(User user, String rawToken, Duration validFor) {
        enqueue(TEMPLATE_INVITATION, user, "/accept-invite", rawToken, validFor);
    }

    private void enqueue(String template, User user, String path, String rawToken, Duration validFor) {
        EmailOutbox email = new EmailOutbox();
        email.setToEmail(user.getEmail());
        email.setTemplate(template);
        email.setPayload(
                Map.of(
                        "fullName", user.getFullName(),
                        "link", properties.frontendBaseUrl() + path + "?token=" + rawToken,
                        "validForMinutes", validFor.toMinutes()));
        outbox.save(email);
    }
}

package com.amanahconnect.mail;

import com.amanahconnect.mail.MailTemplate.Audience;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Every email template, by the name stored in the outbox. A name that is not here cannot be sent. */
@Component
public class MailTemplates {

    private final Map<String, MailTemplate> byName = new LinkedHashMap<>();

    public MailTemplates() {
        // member-facing: sent in the community's name
        add("member-welcome", Audience.MEMBER, p -> "Welcome to " + s(p, "communityName"), false, "communityName", "memberName");
        add("member-invite", Audience.MEMBER, p -> "You are invited to join " + s(p, "communityName"), true, "communityName", "link");
        add("member-registration-approved", Audience.MEMBER, p -> "Your registration with " + s(p, "communityName") + " is approved", false, "communityName", "memberName");
        add("member-registration-rejected", Audience.MEMBER, p -> "Your registration with " + s(p, "communityName"), false, "communityName", "memberName");
        add("member-bill", Audience.MEMBER, p -> "Invoice " + s(p, "invoiceNo") + " from " + s(p, "communityName"), true, "communityName", "memberName", "invoiceNo", "amount", "dueDate");
        add("payment-reminder", Audience.MEMBER, p -> "Reminder: invoice " + s(p, "invoiceNo") + " is due on " + MailFormat.date(s(p, "dueDate")), true, "communityName", "memberName", "invoiceNo", "balance", "dueDate");
        add("overdue-notice", Audience.MEMBER, p -> "Overdue: invoice " + s(p, "invoiceNo") + " from " + s(p, "communityName"), true, "communityName", "memberName", "invoiceNo", "balance", "dueDate");
        add("member-receipt", Audience.MEMBER, p -> "Receipt " + s(p, "receiptNo") + " from " + s(p, "communityName"), false, "communityName", "memberName", "receiptNo", "amount");
        add("member-announcement", Audience.MEMBER, p -> (Boolean.TRUE.equals(p.get("test")) ? "[TEST] " : "") + s(p, "title"), false, "communityName", "title", "bodyHtml");
        add("complaint-update", Audience.MEMBER, p -> "Update on your complaint: " + s(p, "subject"), false, "communityName", "memberName", "subject", "status");

        // platform to a community's admins
        add("password-reset", Audience.ADMIN, p -> "Reset your Amanah Connect password", true, "link");
        add("invitation", Audience.ADMIN, p -> "You are invited to Amanah Connect", true, "link");
        add("two-factor-changed", Audience.ADMIN, p -> "Two-factor authentication was " + s(p, "action"), false, "action");
        add("member-registration-received", Audience.ADMIN, p -> "New registration request: " + s(p, "applicantName"), false, "communityName", "applicantName");
        add("support-message", Audience.ADMIN, p -> "New support message: " + s(p, "subject"), false, "subject", "senderName", "preview");
        add("subscription-expiring", Audience.ADMIN, p -> "Your subscription ends in " + s(p, "daysLeft") + " days", false, "communityName", "periodEnd");
        add("subscription-expired", Audience.ADMIN, p -> "Your subscription has expired", false, "communityName", "periodEnd");
        add("community-suspended", Audience.ADMIN, p -> s(p, "communityName") + " has been suspended", false, "communityName");
        add("community-activated", Audience.ADMIN, p -> s(p, "communityName") + " is active", false, "communityName");
        add("data-export-ready", Audience.ADMIN, p -> "Your community data is ready to download", true, "communityName", "link", "expiresAt");
        add("platform-announcement", Audience.ADMIN, p -> (Boolean.TRUE.equals(p.get("test")) ? "[TEST] " : "") + s(p, "title"), false, "title", "bodyHtml");

        // platform to a prospect, and to its own team
        add("lead-acknowledgement", Audience.PLATFORM, p -> "Thanks for your interest in Amanah Connect", false, "name");
        add("lead-notification", Audience.PLATFORM, p -> "New lead: " + s(p, "name"), false, "name", "email");
    }

    private void add(String name, Audience audience, Function<Map<String, Object>, String> subject, boolean sensitive, String... required) {
        byName.put(name, new MailTemplate(name, audience, subject, List.of(required), sensitive));
    }

    public Optional<MailTemplate> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<MailTemplate> all() {
        return new ArrayList<>(byName.values());
    }

    private static String s(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value).replaceAll("[\\r\\n]+", " ").trim();
    }
}

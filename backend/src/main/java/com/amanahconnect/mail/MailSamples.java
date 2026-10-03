package com.amanahconnect.mail;

import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed sample data for every template: what the super admin's preview shows and what the golden-file test renders. Nothing here may vary between runs. */
public final class MailSamples {

    private MailSamples() {}

    public static Map<String, Object> payload(String template) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("communityName", "Lotus Residents Welfare Association");
        p.put("memberName", "Asha Rao");
        p.put("contactEmail", "office@lotus-residents.example");
        switch (template) {
            case "member-welcome" -> p.put("memberNo", "LOTUS-0042");
            case "member-invite" -> {
                p.put("recipientName", "Ravi Kumar");
                p.put("link", "https://app.amanahconnect.example/join/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-aBc");
                p.put("expiresAt", "2026-06-30T18:30:00Z");
            }
            case "member-registration-approved" -> p.put("memberNo", "LOTUS-0043");
            case "member-registration-rejected" -> { }
            case "member-bill" -> {
                p.put("invoiceNo", "INV-2026-27/000123");
                p.put("description", "Monthly maintenance");
                p.put("period", "2026-05");
                p.put("currency", "INR");
                p.put("amount", "1500.00");
                p.put("balance", "1500.00");
                p.put("dueDate", "2026-05-10");
                p.put("payLink", "https://app.amanahconnect.example/pay/Zy9XwVuTsRqPoNmLkJiHgFeDcBa0987654321_-xYz");
            }
            case "payment-reminder" -> {
                p.put("invoiceNo", "INV-2026-27/000123");
                p.put("currency", "INR");
                p.put("balance", "500.00");
                p.put("dueDate", "2026-05-10");
                p.put("payLink", "https://app.amanahconnect.example/pay/Zy9XwVuTsRqPoNmLkJiHgFeDcBa0987654321_-xYz");
            }
            case "overdue-notice" -> {
                p.put("invoiceNo", "INV-2026-27/000123");
                p.put("currency", "INR");
                p.put("balance", "500.00");
                p.put("dueDate", "2026-05-10");
                p.put("daysOverdue", 7);
                p.put("payLink", "https://app.amanahconnect.example/pay/Zy9XwVuTsRqPoNmLkJiHgFeDcBa0987654321_-xYz");
            }
            case "member-receipt" -> {
                p.put("receiptNo", "RCP-2026-27/000045");
                p.put("currency", "INR");
                p.put("amount", "1000.00");
                p.put("amountInWords", "Rupees One Thousand Only");
                p.put("method", "UPI");
                p.put("receivedOn", "2026-05-12");
                p.put("invoiceNo", "INV-2026-27/000123");
                p.put("balance", "500.00");
            }
            case "member-announcement" -> {
                p.put("title", "Water supply interruption on Monday");
                p.put("bodyHtml", "<p>Dear residents,</p><p>The water supply will be <strong>off on Monday 12 May</strong> from 10:00 to 14:00 for tank cleaning.</p><ul><li>Please store water in advance</li><li>Lifts will keep working</li></ul><p>Details: <a href=\"https://lotus-residents.example/notice\">read the notice</a>.</p>");
            }
            case "complaint-update" -> {
                p.put("subject", "Water leak in the stairwell");
                p.put("status", "IN_PROGRESS");
                p.put("about", "comment");
                p.put("message", "A plumber will visit on Friday between 10 and 12.");
            }
            case "password-reset" -> {
                p.put("fullName", "Meera Nair");
                p.put("link", "https://app.amanahconnect.example/reset-password?token=SAMPLE");
                p.put("validForMinutes", 30);
            }
            case "invitation" -> {
                p.put("fullName", "Meera Nair");
                p.put("link", "https://app.amanahconnect.example/accept-invite?token=SAMPLE");
                p.put("validForMinutes", 4320);
            }
            case "two-factor-changed" -> {
                p.put("fullName", "Meera Nair");
                p.put("action", "turned on");
                p.put("at", "12 May 2026, 10:42 IST");
            }
            case "member-registration-received" -> {
                p.put("applicantName", "Ravi Kumar");
                p.put("reviewLink", "https://app.amanahconnect.example/members/registrations");
            }
            case "support-message" -> {
                p.put("subject", "Cannot send bills");
                p.put("senderName", "Meera Nair");
                p.put("preview", "Bills are not going out since this morning. Could you take a look?");
                p.put("unread", 3);
            }
            case "subscription-expiring" -> {
                p.put("planName", "Growth");
                p.put("periodEnd", "2026-06-30");
                p.put("daysLeft", 14);
            }
            case "subscription-expired" -> p.put("periodEnd", "2026-06-30");
            case "community-suspended" -> p.put("reason", "Your subscription expired and was not renewed.");
            case "community-activated" -> p.put("reason", "");
            case "platform-announcement" -> {
                p.put("title", "20% off yearly plans until 30 June");
                p.put("kind", "OFFER");
                p.put("bodyHtml", "<p>Switch to a <strong>yearly plan</strong> before 30 June and save 20%.</p>");
            }
            case "lead-acknowledgement" -> p.put("name", "Priya Shah");
            case "lead-notification" -> {
                p.put("name", "Priya Shah");
                p.put("email", "priya@example.org");
                p.put("phone", "+91 98765 43210");
                p.put("communityName", "Green Valley Society");
                p.put("size", 240);
                p.put("message", "We would like a demo next week.");
            }
            default -> throw new IllegalArgumentException("No sample for " + template);
        }
        return p;
    }

    /** Branding for the samples: a made-up community, no logo (so the output does not depend on a file). */
    public static MailBranding branding(MailTemplate.Audience audience) {
        if (audience == MailTemplate.Audience.MEMBER) {
            return new MailBranding(audience, "Lotus Residents Welfare Association", null, null, "office@lotus-residents.example", "+91 98765 43210",
                    "12 Lotus Lane, Indiranagar, Bengaluru, Karnataka, 560038", "office@lotus-residents.example", "https://app.amanahconnect.example");
        }
        return new MailBranding(audience, "Amanah Connect", null, null, null, null, null, null, "https://app.amanahconnect.example");
    }
}

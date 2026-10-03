package com.amanahconnect.mail;

import com.amanahconnect.mail.MailTemplate.Audience;
import java.util.ArrayList;
import java.util.List;

/**
 * Who an email appears to come from: the header (name and logo) and the footer (contact details). A member-facing mail carries the
 * community's; everything else carries the platform's.
 *
 * @param logoBytes the logo to embed in the mail (inline, so it shows without "load images"), or null for a text-only header
 * @param replyTo where replies go (the community's contact address for member mail), or null
 */
public record MailBranding(
        Audience audience,
        String name,
        byte[] logoBytes,
        String logoContentType,
        String contactEmail,
        String contactPhone,
        String address,
        String replyTo,
        String appUrl) {

    public static final String LOGO_CID = "brand-logo";

    public boolean hasLogo() {
        return logoBytes != null && logoBytes.length > 0;
    }

    public boolean memberFacing() {
        return audience == Audience.MEMBER;
    }

    /** The footer as plain text (the HTML footer is in the layout template). */
    public String footerText() {
        List<String> lines = new ArrayList<>();
        if (memberFacing()) {
            lines.add("You are receiving this email because you are a member of " + name + ".");
            List<String> contact = new ArrayList<>();
            if (contactEmail != null && !contactEmail.isBlank()) contact.add(contactEmail);
            if (contactPhone != null && !contactPhone.isBlank()) contact.add(contactPhone);
            if (!contact.isEmpty()) lines.add("Contact " + name + ": " + String.join(" · ", contact));
            if (address != null && !address.isBlank()) lines.add(address);
            lines.add("To stop receiving emails like this, or to correct your details, contact the community admin"
                    + (contactEmail == null || contactEmail.isBlank() ? "." : " at " + contactEmail + "."));
            lines.add("Sent with Amanah Connect.");
        } else {
            lines.add("Amanah Connect · Community management you can trust.");
            lines.add("This is an automated message. If you did not expect it, you can ignore it.");
        }
        return String.join("\n", lines);
    }
}

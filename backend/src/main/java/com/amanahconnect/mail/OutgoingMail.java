package com.amanahconnect.mail;

import java.util.List;
import java.util.Map;

/** Everything needed to hand one email to the SMTP server. */
public record OutgoingMail(
        String from,
        String to,
        String replyTo,
        String subject,
        String html,
        String text,
        Map<String, String> headers,
        Inline logo,
        List<Attachment> attachments) {

    public record Inline(String cid, byte[] bytes, String contentType) {}

    public record Attachment(String fileName, byte[] bytes, String contentType) {}
}

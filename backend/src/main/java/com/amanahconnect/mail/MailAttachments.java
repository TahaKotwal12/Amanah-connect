package com.amanahconnect.mail;

import com.amanahconnect.billing.ReceiptPdfService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Files attached to an email, resolved when it is sent (not stored in the outbox). Today: the receipt PDF on a receipt email. */
@Component
public class MailAttachments {

    private static final Logger log = LoggerFactory.getLogger(MailAttachments.class);

    private final ReceiptPdfService receiptPdfs;
    private final EmailProperties properties;

    public MailAttachments(ReceiptPdfService receiptPdfs, EmailProperties properties) {
        this.receiptPdfs = receiptPdfs;
        this.properties = properties;
    }

    public Optional<OutgoingMail.Attachment> forTemplate(String template, UUID communityId, Map<String, Object> payload) {
        if (!"member-receipt".equals(template) || communityId == null || payload.get("receiptId") == null) return Optional.empty();
        try {
            UUID receiptId = UUID.fromString(String.valueOf(payload.get("receiptId")));
            byte[] pdf = receiptPdfs.pdf(communityId, receiptId);
            if (pdf == null || pdf.length == 0 || pdf.length > properties.maxAttachmentBytes()) return Optional.empty();
            String number = String.valueOf(payload.getOrDefault("receiptNo", "receipt")).replaceAll("[^A-Za-z0-9._-]+", "-");
            return Optional.of(new OutgoingMail.Attachment("Receipt-" + number + ".pdf", pdf, "application/pdf"));
        } catch (RuntimeException e) {
            log.warn("Receipt PDF could not be attached to an email ({}); sending it without", e.toString());
            return Optional.empty();
        }
    }
}

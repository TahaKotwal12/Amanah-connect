package com.amanahconnect.mail;

import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP through Jakarta Mail (configured by {@code spring.mail.*}: Amazon SES SMTP in production, Mailpit locally). One connection per
 * mail, so the server's final answer is available and, with SES, carries the message id.
 */
@Component
public class JavaMailSmtpGateway implements SmtpGateway {

    /** SES answers {@code 250 Ok 0100018f...-000000}; other servers answer differently, and then there is no id. */
    private static final Pattern SES_ID = Pattern.compile("^250 Ok ([0-9A-Za-z-]{20,})\\s*$");

    private final ObjectProvider<JavaMailSender> senders;

    public JavaMailSmtpGateway(ObjectProvider<JavaMailSender> senders) {
        this.senders = senders;
    }

    @Override
    public String send(OutgoingMail mail) {
        JavaMailSender sender = senders.getIfAvailable();
        if (!(sender instanceof JavaMailSenderImpl impl) || impl.getHost() == null || impl.getHost().isBlank()) {
            throw new SmtpFailure("SMTP is not configured (spring.mail.host)", false, null);
        }
        try {
            MimeMessage message = build(impl, mail);
            try (Transport transport = impl.getSession().getTransport(impl.getProtocol())) {
                transport.connect(impl.getHost(), impl.getPort(), impl.getUsername(), impl.getPassword());
                transport.sendMessage(message, message.getAllRecipients());
                return messageId(transport);
            }
        } catch (SMTPSendFailedException e) {
            throw new SmtpFailure("SMTP " + e.getReturnCode() + ": " + oneLine(e.getMessage()), e.getReturnCode() >= 500, e);
        } catch (SendFailedException e) {
            throw new SmtpFailure("Rejected: " + oneLine(e.getMessage()), true, e);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new SmtpFailure(oneLine(e.toString()), false, e);
        }
    }

    private static MimeMessage build(JavaMailSenderImpl sender, OutgoingMail mail) throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
        helper.setFrom(jakarta.mail.internet.InternetAddress.parse(mail.from())[0]);
        helper.setTo(mail.to());
        if (mail.replyTo() != null && !mail.replyTo().isBlank()) helper.setReplyTo(mail.replyTo());
        helper.setSubject(mail.subject());
        helper.setText(mail.text(), mail.html());
        if (mail.logo() != null) {
            helper.addInline(mail.logo().cid(), new ByteArrayResource(mail.logo().bytes()), mail.logo().contentType());
        }
        for (OutgoingMail.Attachment a : mail.attachments()) {
            helper.addAttachment(a.fileName(), new ByteArrayResource(a.bytes()), a.contentType());
        }
        for (Map.Entry<String, String> h : mail.headers().entrySet()) {
            message.addHeader(h.getKey(), h.getValue());
        }
        return message;
    }

    private static String messageId(Transport transport) {
        if (transport instanceof org.eclipse.angus.mail.smtp.SMTPTransport smtp) {
            Matcher m = SES_ID.matcher(String.valueOf(smtp.getLastServerResponse()).strip());
            if (m.matches()) return m.group(1);
        }
        return null;
    }

    private static String oneLine(String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return clean.length() > 500 ? clean.substring(0, 500) : clean;
    }
}

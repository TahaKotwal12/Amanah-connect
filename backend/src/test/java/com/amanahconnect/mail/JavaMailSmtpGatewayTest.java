package com.amanahconnect.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.file.MagicBytes;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** The real SMTP path against an in-memory SMTP server: what actually goes on the wire (MIME structure, headers, encodings). */
class JavaMailSmtpGatewayTest {

    private GreenMail server;
    private JavaMailSmtpGateway gateway;

    @BeforeEach
    void start() {
        server = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
        server.start();
        gateway = gatewayFor(server.getSmtp().getPort());
    }

    @AfterEach
    void stop() {
        if (server.getSmtp() != null) server.stop();
    }

    private static JavaMailSmtpGateway gatewayFor(int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "2000");
        sender.getJavaMailProperties().put("mail.smtp.timeout", "2000");
        return new JavaMailSmtpGateway(provider(sender));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        return new ObjectProvider<>() {
            @Override
            public JavaMailSender getObject() { return sender; }
            @Override
            public JavaMailSender getObject(Object... args) { return sender; }
            @Override
            public JavaMailSender getIfAvailable() { return sender; }
            @Override
            public JavaMailSender getIfUnique() { return sender; }
        };
    }

    private OutgoingMail mail(String to) {
        return new OutgoingMail("\"Lotus Residents via Amanah Connect\" <no-reply@amanahconnect.example>", to, "office@lotus.example", "Receipt RCP-2026-27/000045 – ₹1,000.00",
                "<html><body><p>Thank you</p><img src=\"cid:brand-logo\"/></body></html>", "Thank you\n", Map.of("Auto-Submitted", "auto-generated", "List-Unsubscribe", "<mailto:office@lotus.example?subject=Unsubscribe>"),
                new OutgoingMail.Inline("brand-logo", MagicBytes.sample("image/png"), "image/png"),
                List.of(new OutgoingMail.Attachment("Receipt-RCP-1.pdf", MagicBytes.sample("application/pdf"), "application/pdf")));
    }

    private static void collect(Part part, List<String> types, List<String> disposition) throws Exception {
        types.add(part.getContentType().split(";")[0].toLowerCase());
        if (part.getDisposition() != null) disposition.add(part.getDisposition().toLowerCase() + ":" + (part.getFileName() == null ? "" : part.getFileName()));
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                collect(child, types, disposition);
            }
        }
    }

    @Test
    void sendsAMultipartMessageWithTextHtmlInlineLogoAndAttachment() throws Exception {
        gateway.send(mail("member@example.test"));

        assertThat(server.waitForIncomingEmail(3000, 1)).isTrue();
        MimeMessage message = server.getReceivedMessages()[0];
        List<String> types = new ArrayList<>();
        List<String> dispositions = new ArrayList<>();
        collect(message, types, dispositions);
        assertThat(types).contains("multipart/mixed", "multipart/related", "multipart/alternative", "text/plain", "text/html", "image/png", "application/pdf");
        assertThat(dispositions).contains("attachment:Receipt-RCP-1.pdf").anyMatch(d -> d.startsWith("inline"));
        assertThat(message.getHeader("Content-ID", null)).isNull();
        assertThat(message.getSubject()).isEqualTo("Receipt RCP-2026-27/000045 – ₹1,000.00");
        assertThat(message.getFrom()[0].toString()).contains("Lotus Residents via Amanah Connect").contains("no-reply@amanahconnect.example");
        assertThat(message.getReplyTo()[0].toString()).isEqualTo("office@lotus.example");
        assertThat(message.getHeader("Auto-Submitted")[0]).isEqualTo("auto-generated");
        assertThat(message.getHeader("List-Unsubscribe")[0]).contains("mailto:office@lotus.example");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("member@example.test");
    }

    @Test
    void theInlineLogoIsReachableByItsContentId() throws Exception {
        gateway.send(mail("member@example.test"));
        server.waitForIncomingEmail(3000, 1);

        String raw = com.icegreen.greenmail.util.GreenMailUtil.getWholeMessage(server.getReceivedMessages()[0]);

        assertThat(raw).contains("Content-ID: <brand-logo>");
    }

    @Test
    void aMailWithoutLogoOrAttachmentIsStillValid() throws Exception {
        gateway.send(new OutgoingMail("Amanah Connect <no-reply@amanahconnect.example>", "x@example.test", null, "Plain", "<p>Hi</p>", "Hi\n", Map.of(), null, List.of()));

        assertThat(server.waitForIncomingEmail(3000, 1)).isTrue();
        List<String> types = new ArrayList<>();
        collect(server.getReceivedMessages()[0], types, new ArrayList<>());
        assertThat(types).contains("text/plain", "text/html");
        assertThat(server.getReceivedMessages()[0].getReplyTo()[0].toString()).as("no Reply-To means replies go to From").contains("no-reply@");
    }

    @Test
    void anUnreachableServerIsATemporaryFailure() {
        int port = server.getSmtp().getPort();
        server.stop();
        JavaMailSmtpGateway down = gatewayFor(port);

        assertThatThrownBy(() -> down.send(mail("member@example.test"))).isInstanceOfSatisfying(SmtpFailure.class, e -> assertThat(e.permanent()).isFalse());
    }

    @Test
    void noSmtpConfigurationIsAFailureNotACrash() {
        JavaMailSmtpGateway unconfigured = new JavaMailSmtpGateway(provider(new JavaMailSenderImpl()));

        assertThatThrownBy(() -> unconfigured.send(mail("member@example.test"))).isInstanceOfSatisfying(SmtpFailure.class, e -> assertThat(e.getMessage()).contains("SMTP is not configured"));
    }

    @Test
    void aSubjectWithLineBreaksCannotInjectHeaders() throws Exception {
        OutgoingMail evil = new OutgoingMail("a@amanahconnect.example", "x@example.test", null, "Hello\r\nBcc: attacker@example.test", "<p>x</p>", "x\n", Map.of(), null, List.of());

        try {
            gateway.send(evil);
        } catch (RuntimeException expectedToBeRefusedOrNeutralised) {
            return;
        }
        server.waitForIncomingEmail(3000, 1);
        MimeMessage message = server.getReceivedMessages()[0];
        assertThat(message.getHeader("Bcc")).isNull();
        assertThat(message.getAllRecipients()).hasSize(1);
    }
}

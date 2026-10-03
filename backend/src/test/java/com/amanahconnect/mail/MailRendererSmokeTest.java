package com.amanahconnect.mail;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MailRendererSmokeTest {

    @Test
    void renderEverythingToTarget() throws Exception {
        MailTemplates templates = new MailTemplates();
        MailRenderer renderer = new MailRenderer(templates);
        Path out = Path.of("target/mail-preview");
        Files.createDirectories(out);
        for (MailTemplate t : templates.all()) {
            RenderedMail mail = renderer.render(t.name(), MailSamples.payload(t.name()), MailSamples.branding(t.audience()), t.name().equals("member-receipt"));
            Files.writeString(out.resolve(t.name() + ".html"), mail.html());
            Files.writeString(out.resolve(t.name() + ".txt"), mail.subject() + "\n\n" + mail.text());
        }
    }
}

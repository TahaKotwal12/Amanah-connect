package com.amanahconnect.mail;

import com.amanahconnect.announcement.RichText;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders an email from its template (resources {@code mail/NAME.html} and {@code mail/NAME.txt}), the outbox payload and the branding.
 * The HTML uses inline CSS and tables so it holds up in mail clients; the text part is written by hand, not scraped from the HTML.
 *
 * <p>The payload is untrusted: every value is HTML-escaped by the template engine, except announcement bodies, which are sanitised again
 * here (they were already cleaned when saved), and links, which must be http(s) or are dropped.
 */
@Component
public class MailRenderer {

    public static final String DEEP_TEAL = "#07363E";
    public static final String TEAL = "#037077";
    public static final String GOLD = "#C9A227";

    private static final Pattern SAFE_URL = Pattern.compile("^https?://[^\\s\"'<>]+$", Pattern.CASE_INSENSITIVE);

    private final MailTemplates templates;
    private final SpringTemplateEngine engine;
    private final MailFormat format = new MailFormat();

    public MailRenderer(MailTemplates templates) {
        this.templates = templates;
        this.engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver html = new ClassLoaderTemplateResolver();
        html.setResolvablePatterns(java.util.Set.of("mail/*.html"));
        html.setTemplateMode(TemplateMode.HTML);
        html.setCharacterEncoding(StandardCharsets.UTF_8.name());
        html.setOrder(1);
        html.setCacheable(true);
        ClassLoaderTemplateResolver text = new ClassLoaderTemplateResolver();
        text.setResolvablePatterns(java.util.Set.of("mail/*.txt"));
        text.setTemplateMode(TemplateMode.TEXT);
        text.setCharacterEncoding(StandardCharsets.UTF_8.name());
        text.setOrder(2);
        text.setCacheable(true);
        engine.addTemplateResolver(html);
        engine.addTemplateResolver(text);
    }

    public RenderedMail render(String templateName, Map<String, Object> payload, MailBranding branding) {
        return render(templateName, payload, branding, false);
    }

    /** @param attached whether a file (a receipt PDF) is attached to the mail, so the text can say so */
    public RenderedMail render(String templateName, Map<String, Object> payload, MailBranding branding, boolean attached) {
        MailTemplate template = templates.find(templateName).orElseThrow(() -> new MailRenderException("Unknown email template: " + templateName));
        Map<String, Object> p = clean(payload == null ? Map.of() : payload);
        for (String key : template.required()) {
            Object value = p.get(key);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new MailRenderException("Email template " + templateName + " needs \"" + key + "\"");
            }
        }
        if (p.get("bodyHtml") instanceof String html) p.put("bodyText", RichText.text(html));
        String subject = template.subjectFor(p);
        Context context = new Context(java.util.Locale.ENGLISH);
        context.setVariable("p", p);
        context.setVariable("brand", branding);
        context.setVariable("fmt", format);
        context.setVariable("attached", attached);
        context.setVariable("subject", subject);
        context.setVariable("logoCid", MailBranding.LOGO_CID);
        context.setVariable("footerText", branding.footerText());
        context.setVariable("deepTeal", DEEP_TEAL);
        context.setVariable("teal", TEAL);
        context.setVariable("gold", GOLD);
        try {
            String html = engine.process("mail/" + templateName + ".html", context);
            String text = engine.process("mail/" + templateName + ".txt", context);
            return new RenderedMail(subject, html, text.strip() + "\n", branding, template);
        } catch (RuntimeException e) {
            throw new MailRenderException("Could not render email template " + templateName + ": " + e.getMessage(), e);
        }
    }

    /** A map that answers null for a key that is not there, so a template can say {@code p.optionalThing} without blowing up. */
    private static final class LenientMap extends LinkedHashMap<String, Object> {
        @Override
        public boolean containsKey(Object key) {
            return true;
        }
    }

    /** A copy of the payload safe to put in a template. */
    private static Map<String, Object> clean(Map<String, Object> payload) {
        Map<String, Object> out = new LenientMap();
        payload.forEach((key, value) -> {
            if (value instanceof String s) {
                if (key.equals("bodyHtml")) {
                    out.put(key, RichText.sanitize(s));
                } else if (key.toLowerCase().endsWith("link") || key.toLowerCase().endsWith("url")) {
                    out.put(key, SAFE_URL.matcher(s.trim()).matches() ? s.trim() : null);
                } else {
                    out.put(key, s.replace("\u0000", ""));
                }
            } else {
                out.put(key, value);
            }
        });
        return out;
    }
}

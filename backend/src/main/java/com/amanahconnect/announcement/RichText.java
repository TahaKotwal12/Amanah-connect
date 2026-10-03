package com.amanahconnect.announcement;

import java.util.regex.Pattern;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.web.util.HtmlUtils;

/**
 * The only way announcement HTML is accepted. An allow-list: basic text formatting, lists, headings, quotes and links
 * (http, https, mailto only, always rel="nofollow noopener noreferrer"). Everything else is
 * dropped, with the content of script and style removed outright: no images (they track readers), no forms, no
 * iframes, no inline style or classes, no event handlers, no javascript: or data: URLs.
 */
public final class RichText {

    private RichText() {}

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "b", "em", "i", "u", "s", "ul", "ol", "li", "h1", "h2", "h3", "h4", "blockquote", "code", "pre", "hr")
            .allowElements("a")
            .allowUrlProtocols("http", "https", "mailto")
            .allowAttributes("href").onElements("a")
            .requireRelsOnLinks("nofollow", "noopener", "noreferrer")
            .toFactory();

    private static final PolicyFactory TEXT_ONLY = new HtmlPolicyBuilder().toFactory();
    private static final Pattern BLANKS = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern BLANK_LINES = Pattern.compile("(\\s*\\n){3,}");

    /** The safe HTML for storing and sending. */
    public static String sanitize(String html) {
        return html == null ? "" : POLICY.sanitize(html).trim();
    }

    /** The words only, for a plain-text email part and for deciding that a body is really empty. */
    public static String text(String html) {
        if (html == null) return "";
        String asLines = html.replaceAll("(?i)<br\\s*/?>|</p>|</li>|</h[1-4]>|</blockquote>|</pre>", "\n");
        String stripped = HtmlUtils.htmlUnescape(TEXT_ONLY.sanitize(asLines));
        String collapsed = BLANKS.matcher(stripped).replaceAll(" ").replaceAll("(?m)^ +| +$", "");
        return BLANK_LINES.matcher(collapsed).replaceAll("\n\n").trim();
    }

    public static boolean isBlank(String sanitizedHtml) {
        return text(sanitizedHtml).isBlank();
    }
}

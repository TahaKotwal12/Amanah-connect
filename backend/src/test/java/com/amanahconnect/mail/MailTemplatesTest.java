package com.amanahconnect.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.announcement.AnnouncementDispatcher;
import com.amanahconnect.auth.AuthEmails;
import com.amanahconnect.billing.BillingEmails;
import com.amanahconnect.mail.MailTemplate.Audience;
import com.amanahconnect.member.MemberEmails;
import com.amanahconnect.member.PublicInviteService;
import com.amanahconnect.notification.PlatformEmails;
import com.amanahconnect.support.SupportEmails;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Every email template rendered with sample data and compared with a stored golden copy; plus the rules every template must obey. */
class MailTemplatesTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden/mail");
    private static final boolean UPDATE = Boolean.getBoolean("golden.update");

    private final MailTemplates templates = new MailTemplates();
    private final MailRenderer renderer = new MailRenderer(templates);

    private RenderedMail render(MailTemplate t) {
        return renderer.render(t.name(), MailSamples.payload(t.name()), MailSamples.branding(t.audience()), t.name().equals("member-receipt"));
    }

    // ---- golden files ---------------------------------------------------------------------------------------------------

    @Test
    void everyTemplateRendersToItsGoldenFile() throws IOException {
        Files.createDirectories(GOLDEN);
        for (MailTemplate t : templates.all()) {
            RenderedMail mail = render(t);
            compare(t.name() + ".html", mail.html());
            compare(t.name() + ".txt", "Subject: " + mail.subject() + "\n\n" + mail.text());
        }
    }

    private void compare(String file, String actual) throws IOException {
        Path path = GOLDEN.resolve(file);
        if (UPDATE) {
            Files.writeString(path, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(path).as("golden file " + file + " is missing (run with -Dgolden.update=true to create it)").exists();
        assertThat(actual).as("rendering of " + file + " changed; if it is intended, regenerate with -Dgolden.update=true and review the diff").isEqualTo(Files.readString(path, StandardCharsets.UTF_8));
    }

    @Test
    void noGoldenFileIsLeftOverForAnUnknownTemplate() throws IOException {
        try (var files = Files.list(GOLDEN)) {
            List<String> names = files.map(p -> p.getFileName().toString().replaceAll("\\.(html|txt)$", "")).distinct().toList();
            assertThat(names).allSatisfy(n -> assertThat(templates.find(n)).as(n).isPresent());
        }
    }

    // ---- the registry ---------------------------------------------------------------------------------------------------

    @Test
    void everyNameTheCodeQueuesIsARegisteredTemplate() {
        List<String> queued = List.of(
                MemberEmails.WELCOME, "member-invite", PublicInviteService.REGISTRATION_RECEIVED, "member-registration-approved", "member-registration-rejected",
                AuthEmails.TEMPLATE_PASSWORD_RESET, AuthEmails.TEMPLATE_INVITATION, "two-factor-changed", "data-export-ready",
                BillingEmails.BILL, BillingEmails.RECEIPT, BillingEmails.PAYMENT_REMINDER, BillingEmails.OVERDUE_NOTICE,
                AnnouncementDispatcher.MEMBER_TEMPLATE, AnnouncementDispatcher.ADMIN_TEMPLATE, "complaint-update", SupportEmails.TEMPLATE,
                PlatformEmails.COMMUNITY_SUSPENDED, PlatformEmails.COMMUNITY_ACTIVATED, PlatformEmails.SUBSCRIPTION_EXPIRING, PlatformEmails.SUBSCRIPTION_EXPIRED,
                PlatformEmails.LEAD_ACKNOWLEDGEMENT, PlatformEmails.LEAD_NOTIFICATION);
        assertThat(queued).allSatisfy(name -> assertThat(templates.find(name)).as(name).isPresent());
        assertThat(templates.all()).hasSameSizeAs(queued);
    }

    @Test
    void everyTemplateHasHtmlTextAndSampleData() {
        for (MailTemplate t : templates.all()) {
            assertThat(MailTemplatesTest.class.getResource("/mail/" + t.name() + ".html")).as(t.name() + ".html").isNotNull();
            assertThat(MailTemplatesTest.class.getResource("/mail/" + t.name() + ".txt")).as(t.name() + ".txt").isNotNull();
            Map<String, Object> sample = MailSamples.payload(t.name());
            for (String key : t.required()) assertThat(sample.get(key)).as(t.name() + " sample needs " + key).isNotNull();
        }
    }

    @Test
    void onlyTemplatesWithTokensInTheirLinksAreMarkedSensitive() {
        assertThat(templates.all().stream().filter(MailTemplate::sensitive).map(MailTemplate::name))
                .containsExactlyInAnyOrder("member-invite", "member-bill", "payment-reminder", "overdue-notice", "password-reset", "invitation", "data-export-ready");
    }

    // ---- rules every email follows --------------------------------------------------------------------------------------

    @Test
    void everyHtmlIsBrandedResponsiveAndSelfContained() {
        for (MailTemplate t : templates.all()) {
            String html = render(t).html();
            assertThat(html).as(t.name()).startsWith("<!DOCTYPE html>")
                    .contains("#07363E", "#037077", "#C9A227")
                    .contains("name=\"viewport\"", "@media only screen and (max-width: 620px)", "max-width:600px")
                    .contains("<title>");
            assertThat(html).as(t.name() + " must work without scripts, styles files or remote images").doesNotContainPattern("(?i)<script").doesNotContainPattern("(?i)<link ")
                    .doesNotContainPattern("(?i)<img[^>]+src=\"https?:").doesNotContainPattern("(?i)url\\(");
            assertThat(html).as(t.name() + " keeps its styling inline").contains("style=\"");
        }
    }

    @Test
    void everyTemplateHasAReadablePlainTextAlternative() {
        Pattern tag = Pattern.compile("</?[a-zA-Z][^>]*>");
        for (MailTemplate t : templates.all()) {
            RenderedMail mail = render(t);
            assertThat(mail.text()).as(t.name()).isNotBlank().doesNotContainPattern(tag).doesNotContain("&amp;", "&lt;", "th:", "[[", "]]");
            assertThat(mail.text()).as(t.name() + " ends with the footer").contains("Amanah Connect");
        }
    }

    @Test
    void subjectsAreOneLineAndNeverEmpty() {
        for (MailTemplate t : templates.all()) {
            String subject = render(t).subject();
            assertThat(subject).as(t.name()).isNotBlank().doesNotContain("\n").doesNotContain("\r").hasSizeLessThan(150);
        }
    }

    @Test
    void memberFacingMailCarriesTheCommunitysContactDetailsAndAWayToStop() {
        for (MailTemplate t : templates.all().stream().filter(t -> t.audience() == Audience.MEMBER).toList()) {
            RenderedMail mail = render(t);
            for (String body : List.of(mail.html(), mail.text())) {
                assertThat(body).as(t.name()).contains("office@lotus-residents.example", "+91 98765 43210", "12 Lotus Lane", "contact the community admin");
            }
            assertThat(mail.html()).as(t.name() + " names the community in the header").contains("Lotus Residents Welfare Association");
        }
    }

    @Test
    void platformMailCarriesThePlatformsFooterNotACommunitys() {
        for (MailTemplate t : templates.all().stream().filter(t -> t.audience() != Audience.MEMBER).toList()) {
            RenderedMail mail = render(t);
            assertThat(mail.html()).as(t.name()).contains("Amanah Connect").contains("This is an automated message").doesNotContain("You are receiving this email because you are a member");
        }
    }

    @Test
    void theHeaderShowsTheLogoOnlyWhenThereIsOne() {
        MailTemplate welcome = templates.find("member-welcome").orElseThrow();
        MailBranding plain = MailSamples.branding(Audience.MEMBER);
        MailBranding withLogo = new MailBranding(Audience.MEMBER, plain.name(), new byte[] {1, 2, 3}, "image/png", plain.contactEmail(), plain.contactPhone(), plain.address(), plain.replyTo(), plain.appUrl());

        assertThat(renderer.render(welcome.name(), MailSamples.payload(welcome.name()), plain).html()).doesNotContain("cid:brand-logo");
        assertThat(renderer.render(welcome.name(), MailSamples.payload(welcome.name()), withLogo).html()).contains("src=\"cid:brand-logo\"");
    }

    // ---- hostile input ---------------------------------------------------------------------------------------------------

    @Test
    void payloadValuesAreEscapedNotInterpreted() {
        Map<String, Object> p = MailSamples.payload("member-bill");
        p.put("memberName", "<script>alert(1)</script><img src=x onerror=alert(2)>");
        p.put("description", "\"><b onmouseover=alert(3)>x</b>");

        String html = renderer.render("member-bill", p, MailSamples.branding(Audience.MEMBER)).html();

        assertThat(html).doesNotContain("<script>alert(1)").doesNotContain("<img src=x").doesNotContain("<b onmouseover").contains("&lt;script&gt;");
    }

    @Test
    void aSubjectCannotCarryALineBreak() {
        Map<String, Object> p = MailSamples.payload("member-bill");
        p.put("communityName", "Lotus\r\nBcc: attacker@example.test");

        String subject = renderer.render("member-bill", p, MailSamples.branding(Audience.MEMBER)).subject();

        assertThat(subject).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void linksThatAreNotHttpAreDropped() {
        for (String evil : List.of("javascript:alert(1)", "data:text/html;base64,PHNjcmlwdD4=", "JaVaScRiPt:alert(1)", " javascript:alert(1)", "//evil.test/x", "https://ok.test/a\"onclick=\"x", "ftp://x.test")) {
            Map<String, Object> p = MailSamples.payload("member-bill");
            p.put("payLink", evil);

            String html = renderer.render("member-bill", p, MailSamples.branding(Audience.MEMBER)).html();

            assertThat(html.toLowerCase()).as(evil).doesNotContain("javascript:").doesNotContain("data:text").doesNotContain("href=\"//").doesNotContain("onclick").doesNotContain("ftp:");
            assertThat(html).as(evil + ": the pay button goes").doesNotContain("Pay with UPI");
        }
        Map<String, Object> ok = MailSamples.payload("member-bill");
        assertThat(renderer.render("member-bill", ok, MailSamples.branding(Audience.MEMBER)).html()).contains("Pay with UPI");
    }

    @Test
    void announcementHtmlIsSanitisedAgainAtRenderTime() {
        Map<String, Object> p = MailSamples.payload("member-announcement");
        p.put("bodyHtml", "<p>Hello</p><script>alert(1)</script><img src=x onerror=alert(2)><a href=\"javascript:alert(3)\">click</a>");

        RenderedMail mail = renderer.render("member-announcement", p, MailSamples.branding(Audience.MEMBER));

        assertThat(mail.html().toLowerCase()).doesNotContain("<script", "onerror", "javascript:", "<img src=x");
        assertThat(mail.html()).contains("<p>Hello</p>");
        assertThat(mail.text()).contains("Hello").doesNotContain("alert");
    }

    @Test
    void aTestAnnouncementSaysSoInSubjectAndBody() {
        Map<String, Object> p = MailSamples.payload("member-announcement");
        p.put("test", true);

        RenderedMail mail = renderer.render("member-announcement", p, MailSamples.branding(Audience.MEMBER));

        assertThat(mail.subject()).startsWith("[TEST] ");
        assertThat(mail.html()).contains("This is a test");
        assertThat(mail.text()).contains("THIS IS A TEST");
    }

    @Test
    void missingRequiredDataIsAnErrorNotABlankMail() {
        Map<String, Object> p = new LinkedHashMap<>(MailSamples.payload("member-bill"));
        p.remove("invoiceNo");

        assertThatThrownBy(() -> renderer.render("member-bill", p, MailSamples.branding(Audience.MEMBER))).isInstanceOf(MailRenderException.class).hasMessageContaining("invoiceNo");
        assertThatThrownBy(() -> renderer.render("nope", Map.of(), MailSamples.branding(Audience.MEMBER))).isInstanceOf(MailRenderException.class).hasMessageContaining("Unknown email template");
    }

    @Test
    void optionalDataMayBeAbsent() {
        Map<String, Object> p = new LinkedHashMap<>(MailSamples.payload("member-bill"));
        for (String key : List.of("description", "period", "balance", "payLink")) p.remove(key);

        RenderedMail mail = renderer.render("member-bill", p, MailSamples.branding(Audience.MEMBER));

        assertThat(mail.html()).doesNotContain("null").doesNotContain("Pay with UPI");
        assertThat(mail.text()).doesNotContain("null");
    }

    // ---- display formatting -----------------------------------------------------------------------------------------------

    @Test
    void formatsMoneyAndDatesForPeople() {
        assertThat(MailFormat.money("1234567.5", "INR")).isEqualTo("₹12,34,567.50");
        assertThat(MailFormat.money("1500", null)).isEqualTo("₹1,500.00");
        assertThat(MailFormat.money("99.9", "USD")).isEqualTo("USD 99.90");
        assertThat(MailFormat.money("not a number", "INR")).isEqualTo("not a number");
        assertThat(MailFormat.date("2026-05-10")).isEqualTo("10 May 2026");
        assertThat(MailFormat.date("2026-06-30T18:30:00Z")).isEqualTo("30 Jun 2026");
        assertThat(MailFormat.date("soon")).isEqualTo("soon");
        assertThat(MailFormat.date(null)).isEmpty();
    }
}

package com.amanahconnect.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdminEmailIT extends AbstractMailIT {

    private static final String E = "/api/v1/admin/email";

    // ---- template preview (dev and staging) -------------------------------------------------------------------------------

    @Test
    void listsEveryTemplateWithItsAudienceAndASampleSubject() {
        ApiClient.Response r = asSuper("GET", E + "/templates", null);

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        r.json().forEach(t -> names.add(t.get("name").asString()));
        assertThat(names).hasSize(22).contains("member-bill", "password-reset", "lead-notification");
        JsonNode bill = null;
        for (JsonNode t : r.json()) if (t.get("name").asString().equals("member-bill")) bill = t;
        assertThat(bill.get("audience").asString()).isEqualTo("MEMBER");
        assertThat(bill.get("sensitive").asBoolean()).isTrue();
        assertThat(bill.get("sampleSubject").asString()).startsWith("Invoice INV-");
        assertThat(bill.get("requiredFields").toString()).contains("invoiceNo");
    }

    @Test
    void previewsATemplateAsHtmlOrTextWithSampleData() {
        ApiClient.Response html = asSuper("GET", E + "/templates/member-bill/preview", null);
        ApiClient.Response text = asSuper("GET", E + "/templates/member-bill/preview?format=text", null);

        assertThat(html.status()).isEqualTo(200);
        assertThat(html.header("Content-Type")).startsWith("text/html");
        assertThat(html.header("X-Email-Subject")).startsWith("Invoice INV-2026-27/000123 from Lotus");
        assertThat(html.header("Cache-Control")).contains("no-store");
        assertThat(html.header("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(html.body()).startsWith("<!DOCTYPE html>").contains("#07363E", "Asha Rao", "₹1,500.00");
        assertThat(text.header("Content-Type")).startsWith("text/plain");
        assertThat(text.body()).contains("Dear Asha Rao").doesNotContain("<");
        assertThat(smtp.sent).as("a preview sends nothing").isEmpty();
    }

    @Test
    void everyTemplateCanBePreviewed() {
        for (String name : List.of("member-welcome", "member-invite", "member-registration-approved", "member-registration-rejected", "member-bill", "payment-reminder", "overdue-notice",
                "member-receipt", "member-announcement", "complaint-update", "password-reset", "invitation", "two-factor-changed", "member-registration-received", "support-message",
                "subscription-expiring", "subscription-expired", "community-suspended", "community-activated", "platform-announcement", "lead-acknowledgement", "lead-notification")) {
            assertThat(asSuper("GET", E + "/templates/" + name + "/preview", null).status()).as(name).isEqualTo(200);
            assertThat(asSuper("GET", E + "/templates/" + name + "/preview?format=text", null).status()).as(name + " text").isEqualTo(200);
        }
    }

    @Test
    void anUnknownTemplateIsA404() {
        assertThat(asSuper("GET", E + "/templates/no-such-template/preview", null).status()).isEqualTo(404);
        assertThat(asSuper("GET", E + "/templates/..%2F..%2Fapplication/preview", null).status()).isIn(400, 404);
    }

    @Test
    void thePreviewDoesNotExistWhereItIsSwitchedOff() {
        EmailProperties off = new EmailProperties("Amanah <no-reply@x.test>", 6, java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(5), 50, 1, false, "", null);
        AdminEmailService production = new AdminEmailService(new MailTemplates(), new MailRenderer(new MailTemplates()), off, null, null, null, null, java.time.Clock.systemUTC());

        org.assertj.core.api.Assertions.assertThatThrownBy(production::templates).isInstanceOf(com.amanahconnect.common.error.NotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> production.preview("member-bill")).isInstanceOf(com.amanahconnect.common.error.NotFoundException.class);
    }

    @Test
    void onlySuperAdminsReachTheEmailEndpoints() {
        for (String path : List.of("/templates", "/templates/member-bill/preview", "/outbox", "/stats", "/suppressions")) {
            assertThat(call(sessionA, "GET", E + path, null).status()).as(path).isEqualTo(403);
            assertThat(api.get(E + path).status()).as(path + " without login").isEqualTo(401);
        }
        assertThat(call(sessionA, "POST", E + "/suppressions", Map.of("email", "x@example.test")).status()).isEqualTo(403);
        assertThat(call(sessionA, "DELETE", E + "/suppressions/x@example.test", null).status()).isEqualTo(403);
        assertThat(call(sessionA, "POST", E + "/outbox/" + UUID.randomUUID() + "/retry", null).status()).isEqualTo(403);
    }

    // ---- outbox -----------------------------------------------------------------------------------------------------------

    @Test
    void showsTheOutboxWithMaskedAddressesAndFilters() {
        String to = "jane.doe-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        UUID failed = queueSample(communityA.getId(), to, "member-welcome");
        jdbc.update("update email_outbox set status = 'FAILED', error = 'SMTP 550: no such user' where id = ?", failed);
        UUID pending = queueSample(communityB.getId(), email(), "member-bill");

        JsonNode failedList = asSuper("GET", E + "/outbox?status=FAILED&communityId=" + communityA.getId(), null).json();
        JsonNode byTemplate = asSuper("GET", E + "/outbox?template=member-bill&communityId=" + communityB.getId(), null).json();

        assertThat(failedList.get("items")).hasSize(1);
        JsonNode item = failedList.get("items").get(0);
        assertThat(item.get("id").asString()).isEqualTo(failed.toString());
        assertThat(item.get("to").asString()).doesNotContain("jane.doe").contains("@example.test");
        assertThat(item.get("error").asString()).contains("550");
        assertThat(item.get("status").asString()).isEqualTo("FAILED");
        assertThat(byTemplate.get("items").get(0).get("id").asString()).isEqualTo(pending.toString());
        assertThat(asSuper("GET", E + "/outbox?status=WHATEVER", null).status()).isEqualTo(400);
        assertThat(asSuper("GET", E + "/outbox?size=1000", null).status()).isEqualTo(400);
    }

    @Test
    void statsSummariseTheQueue() {
        queueSample(communityA.getId(), email(), "member-welcome");
        UUID failed = queueSample(communityA.getId(), email(), "member-welcome");
        jdbc.update("update email_outbox set status = 'FAILED' where id = ?", failed);
        sender.runOnce(); // sends the pending one

        JsonNode stats = asSuper("GET", E + "/stats", null).json();

        assertThat(stats.get("failed").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.get("sentLast24h").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.has("pending")).isTrue();
        assertThat(stats.has("suppressedAddresses")).isTrue();
    }

    @Test
    void aFailedMailCanBeRetriedAfterTheCauseIsFixed() {
        String to = email();
        UUID id = queueSample(communityA.getId(), to, "member-welcome");
        smtp.failAll(true, "550 mailbox unavailable");
        sender.runOnce();
        assertThat(status(id)).isEqualTo("FAILED");
        smtp.reset();

        ApiClient.Response retry = asSuper("POST", E + "/outbox/" + id + "/retry", null);

        assertThat(retry.status()).as(retry.body()).isEqualTo(200);
        assertThat(retry.json().get("status").asString()).isEqualTo("PENDING");
        assertThat(retry.json().get("attempts").asInt()).isZero();
        assertThat(count("select count(*) from audit_logs where action = 'EMAIL_RETRY_REQUESTED' and entity_id = ?", id)).isEqualTo(1);
        sender.runOnce();
        assertSentOnce(id);
        assertThat(asSuper("POST", E + "/outbox/" + id + "/retry", null).status()).as("a sent mail cannot be retried").isEqualTo(404);
        assertThat(asSuper("POST", E + "/outbox/" + UUID.randomUUID() + "/retry", null).status()).isEqualTo(404);
    }

    // ---- suppression list --------------------------------------------------------------------------------------------------

    @Test
    void anAddressCanBeSuppressedByHandListedAndReleased() {
        String address = email();

        ApiClient.Response added = asSuper("POST", E + "/suppressions", Map.of("email", address.toUpperCase()));
        assertThat(added.status()).as(added.body()).isEqualTo(200);
        assertThat(added.json().get("added").asBoolean()).isTrue();
        assertThat(asSuper("POST", E + "/suppressions", Map.of("email", address)).json().get("added").asBoolean()).as("again is a no-op").isFalse();
        assertThat(jdbc.queryForObject("select reason from email_suppressions where email = ?", String.class, address)).isEqualTo("MANUAL");

        JsonNode list = asSuper("GET", E + "/suppressions?size=100", null).json();
        assertThat(list.get("items").toString()).doesNotContain(address);
        assertThat(list.get("total").asLong()).isGreaterThanOrEqualTo(1);

        UUID id = queueSample(communityA.getId(), address, "member-welcome");
        sender.runOnce();
        assertThat(status(id)).isEqualTo("FAILED");

        assertThat(asSuper("DELETE", E + "/suppressions/" + address, null).status()).isEqualTo(204);
        assertThat(asSuper("DELETE", E + "/suppressions/" + address, null).status()).isEqualTo(404);
        UUID later = queueSample(communityA.getId(), address, "member-welcome");
        sender.runOnce();
        assertThat(status(later)).as("released: mail flows again").isEqualTo("SENT");
        assertThat(count("select count(*) from audit_logs where action = 'EMAIL_SUPPRESSED' and actor_user_id = ?", superAdmin.id())).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'EMAIL_SUPPRESSION_REMOVED' and actor_user_id = ?", superAdmin.id())).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action in ('EMAIL_SUPPRESSED', 'EMAIL_SUPPRESSION_REMOVED') and (coalesce(after::text, '') || coalesce(before::text, '')) ilike ?", "%" + address + "%"))
                .as("the full address is never written to the audit log").isZero();
    }

    @Test
    void badAddressesAreRejected() {
        assertThat(asSuper("POST", E + "/suppressions", Map.of("email", "not-an-address")).status()).isEqualTo(400);
        assertThat(asSuper("POST", E + "/suppressions", Map.of("email", "")).status()).isEqualTo(400);
        assertThat(asSuper("POST", E + "/suppressions", Map.of()).status()).isEqualTo(400);
    }
}

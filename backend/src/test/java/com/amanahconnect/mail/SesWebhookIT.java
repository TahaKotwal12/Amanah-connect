package com.amanahconnect.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.TestSnsCertificateProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SesWebhookIT extends AbstractMailIT {

    private static final String WEBHOOK = "/api/v1/webhooks/ses";

    @Autowired TestSnsCertificateProvider sns;

    @BeforeEach
    void forgetConfirmations() {
        sns.confirmed.clear();
    }

    // ---- building signed messages --------------------------------------------------------------------------------------

    private Map<String, String> notification(Object innerEvent) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Type", "Notification");
        m.put("MessageId", UUID.randomUUID().toString());
        m.put("TopicArn", TestSnsCertificateProvider.TOPIC);
        m.put("Message", innerEvent instanceof String s ? s : toJson(innerEvent));
        m.put("Timestamp", "2026-05-12T10:00:00.000Z");
        m.put("SignatureVersion", "1");
        m.put("SigningCertURL", TestSnsCertificateProvider.CERT_URL);
        return m;
    }

    private Map<String, String> signed(Map<String, String> m) {
        m.put("Signature", sns.sign(m, m.get("SignatureVersion")));
        return m;
    }

    private ApiClient.Response post(Map<String, String> message, String... headers) {
        return api.postRaw(WEBHOOK, toJson(message), headers);
    }

    private Map<String, Object> bounce(String type, String... addresses) {
        List<Map<String, Object>> recipients = new java.util.ArrayList<>();
        for (String a : addresses) recipients.add(Map.of("emailAddress", a, "status", "5.1.1", "diagnosticCode", "smtp; 550 5.1.1 user unknown"));
        return Map.of("notificationType", "Bounce", "bounce", Map.of("bounceType", type, "bounceSubType", "General", "bouncedRecipients", recipients, "timestamp", "2026-05-12T10:00:00.000Z"),
                "mail", Map.of("messageId", "0100018f-aaaa", "source", "no-reply@amanahconnect.example"));
    }

    private Map<String, Object> complaint(String... addresses) {
        List<Map<String, Object>> recipients = new java.util.ArrayList<>();
        for (String a : addresses) recipients.add(Map.of("emailAddress", a));
        return Map.of("notificationType", "Complaint", "complaint", Map.of("complainedRecipients", recipients, "complaintFeedbackType", "abuse", "timestamp", "2026-05-12T10:00:00.000Z"),
                "mail", Map.of("messageId", "0100018f-bbbb"));
    }

    private boolean suppressed(String address) {
        return count("select count(*) from email_suppressions where email = ?", address) == 1;
    }

    // ---- bounces and complaints ---------------------------------------------------------------------------------------

    @Test
    void aPermanentBounceSuppressesEveryBouncedRecipient() {
        String a = email();
        String b = email();

        ApiClient.Response r = post(signed(notification(bounce("Permanent", a, b.toUpperCase()))));

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().get("suppressed").asInt()).isEqualTo(2);
        assertThat(suppressed(a)).isTrue();
        assertThat(suppressed(b)).as("case does not matter").isTrue();
        Map<String, Object> row = jdbc.queryForMap("select reason, source, detail::text as detail from email_suppressions where email = ?", a);
        assertThat(row.get("reason")).isEqualTo("BOUNCE");
        assertThat(row.get("source")).isEqualTo("SES");
        assertThat(row.get("detail").toString()).contains("Permanent", "0100018f-aaaa");
    }

    @Test
    void aTemporaryBounceChangesNothing() {
        String a = email();

        ApiClient.Response r = post(signed(notification(bounce("Transient", a))));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("suppressed").asInt()).isZero();
        assertThat(suppressed(a)).isFalse();
    }

    @Test
    void aComplaintSuppressesTheComplainer() {
        String a = email();

        ApiClient.Response r = post(signed(notification(complaint(a))));

        assertThat(r.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select reason from email_suppressions where email = ?", String.class, a)).isEqualTo("COMPLAINT");
    }

    @Test
    void theEventPublishingFormatIsUnderstoodToo() {
        String a = email();
        Map<String, Object> event = new LinkedHashMap<>(bounce("Permanent", a));
        event.put("eventType", "Bounce");
        event.remove("notificationType");

        post(signed(notification(event)));

        assertThat(suppressed(a)).isTrue();
    }

    @Test
    void theSameBounceTwiceIsOneEntryAndTheFirstReasonIsKept() {
        String a = email();
        post(signed(notification(bounce("Permanent", a))));
        post(signed(notification(bounce("Permanent", a))));
        post(signed(notification(complaint(a))));

        assertThat(count("select count(*) from email_suppressions where email = ?", a)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reason from email_suppressions where email = ?", String.class, a)).isEqualTo("BOUNCE");
    }

    @Test
    void aSuppressionIsAuditedWithoutTheFullAddress() {
        String a = email();
        post(signed(notification(bounce("Permanent", a))));

        List<Map<String, Object>> audit = jdbc.queryForList("select after::text as after from audit_logs where action = 'EMAIL_SUPPRESSED' order by created_at desc limit 1");

        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("after").toString()).contains("BOUNCE").doesNotContain(a);
    }

    @Test
    void afterABounceTheSenderRefusesTheAddress() {
        String a = email();
        UUID before = queueSample(communityA.getId(), a, "member-welcome");
        post(signed(notification(bounce("Permanent", a))));

        sender.runOnce();

        assertThat(status(before)).isEqualTo("FAILED");
        assertThat(smtp.sentTo(a)).isEmpty();
    }

    @Test
    void anEventWithoutRecipientsOrOfAnotherKindIsAcceptedAndIgnored() {
        assertThat(post(signed(notification(Map.of("notificationType", "Bounce", "bounce", Map.of("bounceType", "Permanent"))))).status()).isEqualTo(200);
        assertThat(post(signed(notification(Map.of("notificationType", "Delivery", "mail", Map.of("messageId", "x"))))).status()).isEqualTo(200);
        assertThat(post(signed(notification("this is not json, just a console test message"))).status()).isEqualTo(200);
        assertThat(count("select count(*) from email_suppressions")).isZero();
    }

    // ---- trust: only Amazon's signed messages ------------------------------------------------------------------------------

    @Test
    void anUnsignedMessageIsRefused() {
        String a = email();
        Map<String, String> m = notification(bounce("Permanent", a));

        ApiClient.Response r = post(m);

        assertThat(r.status()).isEqualTo(403);
        assertThat(suppressed(a)).isFalse();
    }

    @Test
    void aForgedSignatureIsRefused() {
        String a = email();
        Map<String, String> m = notification(bounce("Permanent", a));
        m.put("Signature", sns.forge(m, "1"));

        assertThat(post(m).status()).isEqualTo(403);
        assertThat(suppressed(a)).isFalse();
    }

    @Test
    void aTamperedMessageIsRefused() {
        String victim = email();
        Map<String, String> m = signed(notification(bounce("Transient", victim)));
        m.put("Message", toJson(bounce("Permanent", victim))); // changed after signing

        assertThat(post(m).status()).isEqualTo(403);
        assertThat(suppressed(victim)).isFalse();
        Map<String, String> other = signed(notification(bounce("Permanent", email())));
        other.put("MessageId", "someone-elses-id");
        assertThat(post(other).status()).as("any signed field counts").isEqualTo(403);
    }

    @Test
    void aMessageFromATopicThatIsNotOursIsRefusedEvenIfCorrectlySigned() {
        String a = email();
        Map<String, String> m = notification(bounce("Permanent", a));
        m.put("TopicArn", "arn:aws:sns:ap-south-1:999999999999:someone-elses-topic");

        assertThat(post(signed(m)).status()).isEqualTo(403);
        assertThat(suppressed(a)).isFalse();
    }

    @Test
    void theSigningCertificateMustComeFromAmazon() {
        for (String url : List.of(
                "http://sns.ap-south-1.amazonaws.com/SimpleNotificationService-test.pem",
                "https://evil.example/SimpleNotificationService-test.pem",
                "https://sns.ap-south-1.amazonaws.com.evil.example/cert.pem",
                "https://user:pw@sns.ap-south-1.amazonaws.com/cert.pem",
                "https://sns.ap-south-1.amazonaws.com:8443/cert.pem",
                "https://sns.ap-south-1.amazonaws.com/not-a-certificate.txt",
                "https://s3.amazonaws.com/cert.pem",
                "file:///etc/passwd",
                "")) {
            String a = email();
            Map<String, String> m = notification(bounce("Permanent", a));
            m.put("SigningCertURL", url);

            assertThat(post(signed(m)).status()).as(url).isEqualTo(403);
            assertThat(suppressed(a)).as(url).isFalse();
        }
    }

    @Test
    void signatureVersionTwoIsAcceptedAndOthersAreNot() {
        String a = email();
        Map<String, String> v2 = notification(bounce("Permanent", a));
        v2.put("SignatureVersion", "2");
        assertThat(post(signed(v2)).status()).isEqualTo(200);
        assertThat(suppressed(a)).isTrue();

        Map<String, String> v3 = notification(bounce("Permanent", email()));
        v3.put("SignatureVersion", "3");
        v3.put("Signature", sns.sign(v3, "1"));
        assertThat(post(v3).status()).isEqualTo(403);

        Map<String, String> missing = signed(notification(bounce("Permanent", email())));
        missing.remove("SignatureVersion");
        assertThat(post(missing).status()).isEqualTo(403);
    }

    @Test
    void garbageIsRefusedWithoutDetail() {
        for (String body : List.of("", "not json", "[]", "{}", "null", "{\"Type\": 5}", "{\"Type\":\"Notification\"}")) {
            ApiClient.Response r = api.postRaw(WEBHOOK, body);
            assertThat(r.status()).as(body).isEqualTo(403);
            assertThat(r.body()).as("no hint about what was wrong").doesNotContain("signature", "certificate", "topic");
        }
    }

    @Test
    void aHugeBodyIsRefused() {
        ApiClient.Response r = api.postRaw(WEBHOOK, "{\"x\":\"" + "a".repeat(300 * 1024) + "\"}");

        assertThat(r.status()).isEqualTo(413);
    }

    @Test
    void itNeedsNoLoginAndIgnoresAStaleBearerToken() {
        String a = email();
        Map<String, String> m = signed(notification(bounce("Permanent", a)));

        ApiClient.Response r = post(m, "Authorization", "Bearer not-a-real-token");

        assertThat(r.status()).isEqualTo(200);
        assertThat(suppressed(a)).isTrue();
    }

    @Test
    void onlyPostIsAccepted() {
        assertThat(api.get(WEBHOOK).status()).isIn(401, 403, 404, 405);
        assertThat(api.get("/api/v1/webhooks/other").status()).isIn(401, 403, 404);
    }

    // ---- subscription confirmation ----------------------------------------------------------------------------------------

    private Map<String, String> subscription(String subscribeUrl) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Type", "SubscriptionConfirmation");
        m.put("MessageId", UUID.randomUUID().toString());
        m.put("Token", "2336412f37fb687f5d51e6e2425f004aed7a");
        m.put("TopicArn", TestSnsCertificateProvider.TOPIC);
        m.put("Message", "You have chosen to subscribe to the topic");
        m.put("SubscribeURL", subscribeUrl);
        m.put("Timestamp", "2026-05-12T10:00:00.000Z");
        m.put("SignatureVersion", "1");
        m.put("SigningCertURL", TestSnsCertificateProvider.CERT_URL);
        return m;
    }

    @Test
    void aSignedSubscriptionConfirmationIsConfirmed() {
        String url = "https://sns.ap-south-1.amazonaws.com/?Action=ConfirmSubscription&TopicArn=" + TestSnsCertificateProvider.TOPIC + "&Token=abc";

        ApiClient.Response r = post(signed(subscription(url)));

        assertThat(r.status()).isEqualTo(200);
        assertThat(sns.confirmed).hasSize(1);
        assertThat(sns.confirmed.get(0).getHost()).isEqualTo("sns.ap-south-1.amazonaws.com");
    }

    @Test
    void aSubscriptionConfirmationPointingElsewhereIsNeverFetched() {
        for (String url : List.of("https://evil.example/confirm", "http://sns.ap-south-1.amazonaws.com/?Token=x", "https://169.254.169.254/latest/meta-data", "https://localhost/")) {
            assertThat(post(signed(subscription(url))).status()).as(url).isEqualTo(403);
        }
        assertThat(sns.confirmed).isEmpty();
    }

    @Test
    void anUnsignedSubscriptionConfirmationIsNeverFetched() {
        Map<String, String> m = subscription("https://sns.ap-south-1.amazonaws.com/?Token=x");

        assertThat(post(m).status()).isEqualTo(403);
        assertThat(sns.confirmed).isEmpty();
    }

    @Test
    void anUnsubscribeConfirmationIsAcknowledgedAndIgnored() {
        Map<String, String> m = subscription("https://sns.ap-south-1.amazonaws.com/?Token=x");
        m.put("Type", "UnsubscribeConfirmation");

        assertThat(post(signed(m)).status()).isEqualTo(200);
        assertThat(sns.confirmed).isEmpty();
    }
}

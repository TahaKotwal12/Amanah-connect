package com.amanahconnect.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.ApiClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The notices that were missing: registration approved/rejected for the applicant, and "two-factor changed" for the account owner. */
class NoticeEmailsIT extends AbstractMailIT {

    private static final String REGISTRATIONS = "/api/v1/community/registrations";

    private UUID pendingRegistration(String name, String address, boolean consent) {
        ApiClient.Response invite = asA("POST", "/api/v1/community/member-invites", Map.of());
        assertThat(invite.status()).isEqualTo(201);
        String link = invite.json().get("link").asString();
        String token = link.substring(link.lastIndexOf('/') + 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", name);
        body.put("email", address);
        body.put("consentEmail", consent);
        assertThat(api.post("/api/v1/public/invites/" + token + "/register", body).status()).isEqualTo(202);
        return jdbc.queryForObject("select id from member_registrations where community_id = ? and email = ?", UUID.class, communityA.getId(), address);
    }

    private List<Map<String, Object>> mails(String to, String template) {
        return outbox(to, template);
    }

    // ---- registration ----------------------------------------------------------------------------------------------------

    @Test
    void approvalSendsTheWelcomeNotAnExtraApprovalNotice() {
        String to = email();
        UUID registration = pendingRegistration("Asha", to, true);

        assertThat(asA("POST", REGISTRATIONS + "/" + registration + "/approve", Map.of()).status()).isEqualTo(200);

        assertThat(mails(to, "member-welcome")).hasSize(1);
        assertThat(mails(to, "member-registration-approved")).isEmpty();
    }

    @Test
    void withWelcomeEmailsOffTheApplicantStillLearnsTheyAreApproved() {
        jdbc.update("update notification_settings set send_welcome = false where community_id = ?", communityA.getId());
        String to = email();
        UUID registration = pendingRegistration("Bina", to, true);

        JsonNode approved = asA("POST", REGISTRATIONS + "/" + registration + "/approve", Map.of()).json();

        assertThat(mails(to, "member-welcome")).isEmpty();
        List<Map<String, Object>> notice = mails(to, "member-registration-approved");
        assertThat(notice).hasSize(1);
        assertThat(notice.get(0).get("community_id").toString()).isEqualTo(communityA.getId().toString());
        assertThat(notice.get(0).get("payload").toString()).contains(approved.get("memberNo").asString(), "Bina");
    }

    @Test
    void rejectionTellsTheApplicantButNeverWhy() {
        String to = email();
        UUID registration = pendingRegistration("Chitra", to, true);

        assertThat(asA("POST", REGISTRATIONS + "/" + registration + "/reject", Map.of("reason", "Not a resident, looks like spam")).status()).isEqualTo(200);

        List<Map<String, Object>> notice = mails(to, "member-registration-rejected");
        assertThat(notice).hasSize(1);
        assertThat(notice.get(0).get("payload").toString()).contains("Chitra").doesNotContain("spam").doesNotContain("resident");
        sender.runOnce();
        assertThat(smtp.sentTo(to).get(0).html()).contains("not able to approve").doesNotContain("spam");
    }

    @Test
    void anApplicantWhoDidNotAgreeToEmailGetsNothing() {
        String to = email();
        UUID registration = pendingRegistration("Dev", to, false);

        asA("POST", REGISTRATIONS + "/" + registration + "/reject", Map.of("reason", "no"));

        assertThat(mails(to, "member-registration-rejected")).isEmpty();
    }

    @Test
    void anExhaustedQuotaSkipsTheNoticeButNotTheDecision() {
        String to = email();
        UUID registration = pendingRegistration("Esha", to, true);
        long queued = count("select count(*) from email_outbox where community_id = ?", communityA.getId());
        Plan none = data.customPlan("No emails left", Map.of("emails_per_month", queued), Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", none.getId(), communityA.getId());

        ApiClient.Response rejected = asA("POST", REGISTRATIONS + "/" + registration + "/reject", Map.of("reason", "no"));

        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.json().get("status").asString()).isEqualTo("REJECTED");
        assertThat(mails(to, "member-registration-rejected")).isEmpty();
    }

    // ---- two-factor ------------------------------------------------------------------------------------------------------

    @Test
    void turningTwoFactorOnAndOffEmailsTheAccountOwner() {
        var admin = users.communityAdminOf(data.community());
        Session session = loginOk(admin);
        ApiClient.Response setup = call(session, "POST", "/api/v1/auth/2fa/setup", null);
        assertThat(setup.status()).as(setup.body()).isEqualTo(200);
        String secret = setup.json().get("secret").asString();

        ApiClient.Response enabled = call(session, "POST", "/api/v1/auth/2fa/enable", Map.of("code", currentCode(secret)));

        assertThat(enabled.status()).as(enabled.body()).isEqualTo(200);
        List<Map<String, Object>> notices = mails(admin.email(), "two-factor-changed");
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).get("payload").toString()).contains("turned on").doesNotContain(secret);
        assertThat(notices.get(0).get("community_id")).isNull();
        sender.runOnce();
        assertThat(smtp.sentTo(admin.email()).get(0).subject()).isEqualTo("Two-factor authentication was turned on");
    }

    private String currentCode(String base32Secret) {
        return new com.amanahconnect.auth.TotpService(java.time.Clock.systemUTC()).codeAt(base32Secret, java.time.Instant.now());
    }
}

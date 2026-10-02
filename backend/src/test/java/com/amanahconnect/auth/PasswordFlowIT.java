package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.AuthTestUsers.TestUser;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PasswordFlowIT extends AbstractAuthIT {

    static final String NEW_PASSWORD = "Another-Strong-Pass-42";

    @Autowired InvitationService invitations;
    @Autowired UserRepository userRepository;

    private List<Map<String, Object>> emails(String template, String to) {
        return jdbc.queryForList("select * from email_outbox where template = ? and to_email = ? order by created_at", template, to);
    }

    /** The raw token only ever exists in the emailed link; read it back from the outbox like the mailer would. */
    private String tokenFromLatestEmail(String template, String to) {
        List<Map<String, Object>> rows = emails(template, to);
        assertThat(rows).as("an email was queued").isNotEmpty();
        String payload = rows.get(rows.size() - 1).get("payload").toString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(payload);
        assertThat(m.find()).as(payload).isTrue();
        return m.group(1);
    }

    private ApiClient.Response forgot(String email) {
        return api.post("/api/v1/auth/password/forgot", Map.of("email", email));
    }

    private ApiClient.Response reset(String token, String password) {
        return api.post("/api/v1/auth/password/reset", Map.of("token", token, "newPassword", password));
    }

    // ---- forgot -----------------------------------------------------------------------------

    @Test
    void forgotIsAcceptedIdenticallyForKnownAndUnknownEmails() {
        TestUser admin = users.communityAdmin();

        ApiClient.Response known = forgot(admin.email());
        ApiClient.Response unknown = forgot("nobody-" + UUID.randomUUID() + "@example.test");

        assertThat(known.status()).isEqualTo(202).isEqualTo(unknown.status());
        assertThat(known.body()).isEmpty();
        assertThat(unknown.body()).isEmpty();
        assertThat(known.header("Content-Type")).isEqualTo(unknown.header("Content-Type"));
        assertThat(known.header("Content-Length")).isEqualTo(unknown.header("Content-Length"));
        assertThat(emails("password-reset", admin.email())).hasSize(1);
    }

    @Test
    void forgotStoresOnlyTheTokenHashAndAuditsBothCases() {
        TestUser admin = users.communityAdmin();
        forgot(admin.email());
        String stranger = "stranger-" + UUID.randomUUID() + "@example.test";
        forgot(stranger);

        String raw = tokenFromLatestEmail("password-reset", admin.email());

        assertThat(jdbc.queryForObject("select count(*) from auth_tokens where token_hash = ?", Long.class, raw)).isZero();
        Map<String, Object> row = jdbc.queryForMap("select * from auth_tokens where token_hash = ?", hash(raw));
        assertThat(row.get("purpose")).isEqualTo("PASSWORD_RESET");
        assertThat(row.get("used_at")).isNull();
        Duration validity = Duration.between(((Timestamp) row.get("created_at")).toInstant(), ((Timestamp) row.get("expires_at")).toInstant());
        assertThat(validity).isBetween(Duration.ofMinutes(30).minusSeconds(2), Duration.ofMinutes(30));
        assertThat(auditCount(admin.id(), "PASSWORD_RESET_REQUESTED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'PASSWORD_RESET_REQUESTED' and actor_user_id is null and after->>'attemptedEmail' = 's***@example.test'", Long.class)).isPositive();
        assertThat(emails("password-reset", stranger)).as("nothing is sent to unknown addresses").isEmpty();
    }

    @Test
    void forgotDoesNotFloodAnInbox() {
        TestUser admin = users.communityAdmin();

        for (int i = 0; i < 6; i++) {
            assertThat(forgot(admin.email()).status()).isEqualTo(202);
        }

        assertThat(emails("password-reset", admin.email())).as("capped per hour, silently").hasSize(3);
    }

    @Test
    void disabledAccountsGetNoResetEmail() {
        TestUser disabled = users.create(UserRole.COMMUNITY_ADMIN, UserStatus.DISABLED);

        assertThat(forgot(disabled.email()).status()).isEqualTo(202);
        assertThat(emails("password-reset", disabled.email())).isEmpty();
    }

    // ---- reset ------------------------------------------------------------------------------

    @Test
    void resetSetsTheNewPasswordAndTheLinkWorksOnlyOnce() {
        TestUser admin = users.communityAdmin();
        forgot(admin.email());
        String token = tokenFromLatestEmail("password-reset", admin.email());

        ApiClient.Response first = reset(token, NEW_PASSWORD);
        ApiClient.Response second = reset(token, "Yet-Another-Strong-Pass-7");

        assertThat(first.status()).isEqualTo(204);
        assertThat(second.status()).as("single use").isEqualTo(400);
        assertThat(second.code()).isEqualTo("INVALID_TOKEN");
        assertThat(api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", NEW_PASSWORD)).status()).isEqualTo(200);
        assertThat(login(admin).status()).as("old password no longer works").isEqualTo(401);
        assertThat(auditCount(admin.id(), "PASSWORD_RESET_COMPLETED")).isEqualTo(1);
    }

    @Test
    void resetRevokesEverySessionAndClearsALockout() {
        TestUser admin = users.communityAdmin();
        Session laptop = loginOk(admin);
        Session phone = loginOk(admin);
        for (int i = 0; i < 5; i++) {
            api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", "Wrong-Password-" + i));
        }
        assertThat(login(admin).status()).isEqualTo(423);
        forgot(admin.email());
        String token = tokenFromLatestEmail("password-reset", admin.email());

        reset(token, NEW_PASSWORD);

        assertThat(refresh(laptop.refreshToken()).status()).isEqualTo(401);
        assertThat(refresh(phone.refreshToken()).status()).isEqualTo(401);
        assertThat(api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", NEW_PASSWORD)).status()).as("lock cleared").isEqualTo(200);
    }

    @Test
    void aWeakPasswordIsRefusedWithoutBurningTheLink() {
        TestUser admin = users.communityAdmin();
        forgot(admin.email());
        String token = tokenFromLatestEmail("password-reset", admin.email());

        ApiClient.Response weak = reset(token, "short");
        ApiClient.Response common = reset(token, "Password123!");

        assertThat(weak.status()).isEqualTo(422);
        assertThat(weak.code()).isEqualTo("PASSWORD_POLICY_VIOLATION");
        assertThat(weak.json().get("errors").size()).isPositive();
        assertThat(common.status()).isEqualTo(422);
        assertThat(reset(token, NEW_PASSWORD).status()).as("the link still works").isEqualTo(204);
    }

    @Test
    void anExpiredOrUnknownTokenIsRejectedTheSameWay() {
        TestUser admin = users.communityAdmin();
        forgot(admin.email());
        String token = tokenFromLatestEmail("password-reset", admin.email());
        jdbc.update("update auth_tokens set expires_at = now() - interval '1 second' where token_hash = ?", hash(token));

        ApiClient.Response expired = reset(token, NEW_PASSWORD);
        ApiClient.Response unknown = reset("A".repeat(43), NEW_PASSWORD);

        assertThat(expired.status()).isEqualTo(400);
        assertThat(expired.code()).isEqualTo("INVALID_TOKEN");
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.json().get("detail")).isEqualTo(expired.json().get("detail"));
    }

    @Test
    void aNewResetLinkDoesNotInvalidateOlderOnesButUsingOneRetiresTheRest() {
        TestUser admin = users.communityAdmin();
        forgot(admin.email());
        String first = tokenFromLatestEmail("password-reset", admin.email());
        forgot(admin.email());
        String second = tokenFromLatestEmail("password-reset", admin.email());
        assertThat(second).isNotEqualTo(first);

        assertThat(reset(second, NEW_PASSWORD).status()).isEqualTo(204);

        assertThat(reset(first, "Yet-Another-Strong-Pass-7").status()).as("siblings retired after a successful reset").isEqualTo(400);
    }

    @Test
    void twoConcurrentUsesOfOneLinkCannotBothSucceed() throws Exception {
        TestUser admin = users.communityAdmin();
        forgot(admin.email());
        String token = tokenFromLatestEmail("password-reset", admin.email());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (String password : List.of(NEW_PASSWORD, "Yet-Another-Strong-Pass-7")) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return reset(token, password).status();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(204, 400);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- change password --------------------------------------------------------------------

    @Test
    void changePasswordNeedsTheCurrentPasswordAndSignsEveryoneOut() {
        TestUser admin = users.communityAdmin();
        Session session = loginOk(admin);
        Session other = loginOk(admin);

        ApiClient.Response wrong = api.post("/api/v1/auth/password/change", Map.of("currentPassword", "Wrong-Password-1", "newPassword", NEW_PASSWORD), "Authorization", session.bearer());
        assertThat(wrong.status()).isEqualTo(403);
        assertThat(wrong.code()).isEqualTo("INVALID_CURRENT_PASSWORD");
        assertThat(((Number) userRow(admin.id()).get("failed_attempts")).intValue()).as("counts toward lockout").isEqualTo(1);

        ApiClient.Response ok = api.post("/api/v1/auth/password/change", Map.of("currentPassword", admin.password(), "newPassword", NEW_PASSWORD), "Authorization", session.bearer());

        assertThat(ok.status()).isEqualTo(204);
        assertThat(ok.rawSetCookie("amanah_refresh")).contains("Max-Age=0");
        assertThat(refresh(session.refreshToken()).status()).isEqualTo(401);
        assertThat(refresh(other.refreshToken()).status()).isEqualTo(401);
        assertThat(api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", NEW_PASSWORD)).status()).isEqualTo(200);
        assertThat(auditCount(admin.id(), "PASSWORD_CHANGED")).isEqualTo(1);
    }

    @Test
    void changePasswordEnforcesThePolicyAndRefusesTheSamePassword() {
        TestUser admin = users.communityAdmin();
        Session session = loginOk(admin);

        ApiClient.Response weak = api.post("/api/v1/auth/password/change", Map.of("currentPassword", admin.password(), "newPassword", "tooshort"), "Authorization", session.bearer());
        ApiClient.Response same = api.post("/api/v1/auth/password/change", Map.of("currentPassword", admin.password(), "newPassword", admin.password()), "Authorization", session.bearer());

        assertThat(weak.code()).isEqualTo("PASSWORD_POLICY_VIOLATION");
        assertThat(same.code()).isEqualTo("PASSWORD_POLICY_VIOLATION");
        assertThat(refresh(session.refreshToken()).status()).as("nothing changed, session intact").isEqualTo(200);
    }

    @Test
    void changePasswordRequiresAuthentication() {
        assertThat(api.post("/api/v1/auth/password/change", Map.of("currentPassword", "a", "newPassword", "b")).status()).isEqualTo(401);
    }

    // ---- invitations ------------------------------------------------------------------------

    private TestUser invitee(UserRole role) {
        return users.create(role, UserStatus.INVITED);
    }

    private void invite(TestUser invitee) {
        invitations.invite(userRepository.findById(invitee.id()).orElseThrow(), users.superAdmin().id());
    }

    @Test
    void anInvitedAdminSetsAPasswordOnceAndThenSignsIn() {
        TestUser invitee = invitee(UserRole.COMMUNITY_ADMIN);
        invite(invitee);
        String token = tokenFromLatestEmail("invitation", invitee.email());

        Map<String, Object> stored = jdbc.queryForMap("select * from auth_tokens where token_hash = ?", hash(token));
        assertThat(stored.get("purpose")).isEqualTo("INVITATION");
        assertThat(Duration.between(((Timestamp) stored.get("created_at")).toInstant(), ((Timestamp) stored.get("expires_at")).toInstant())).isBetween(Duration.ofHours(48).minusSeconds(2), Duration.ofHours(48));

        ApiClient.Response accept = api.post("/api/v1/auth/accept-invite", Map.of("token", token, "newPassword", NEW_PASSWORD));
        ApiClient.Response again = api.post("/api/v1/auth/accept-invite", Map.of("token", token, "newPassword", "Yet-Another-Strong-Pass-7"));

        assertThat(accept.status()).isEqualTo(200);
        assertThat(accept.json().get("mfaSetupRequired").asBoolean()).as("not required for a plain community admin").isFalse();
        assertThat(again.status()).as("single use").isEqualTo(400);
        assertThat(userRow(invitee.id()).get("status")).isEqualTo("ACTIVE");
        assertThat(api.post("/api/v1/auth/login", Map.of("email", invitee.email(), "password", NEW_PASSWORD)).status()).isEqualTo(200);
        assertThat(auditCount(invitee.id(), "INVITATION_ACCEPTED")).isEqualTo(1);
    }

    @Test
    void aSuperAdminInviteeMustThenSetUpTwoFactor() {
        TestUser invitee = invitee(UserRole.SUPER_ADMIN);
        invite(invitee);
        String token = tokenFromLatestEmail("invitation", invitee.email());

        ApiClient.Response accept = api.post("/api/v1/auth/accept-invite", Map.of("token", token, "newPassword", NEW_PASSWORD));

        assertThat(accept.json().get("mfaSetupRequired").asBoolean()).isTrue();
    }

    @Test
    void anExpiredInvitationOrAWeakPasswordFails() {
        TestUser invitee = invitee(UserRole.COMMUNITY_ADMIN);
        invite(invitee);
        String token = tokenFromLatestEmail("invitation", invitee.email());

        ApiClient.Response weak = api.post("/api/v1/auth/accept-invite", Map.of("token", token, "newPassword", "password"));
        assertThat(weak.status()).isEqualTo(422);

        jdbc.update("update auth_tokens set expires_at = now() - interval '1 second' where token_hash = ?", hash(token));
        ApiClient.Response expired = api.post("/api/v1/auth/accept-invite", Map.of("token", token, "newPassword", NEW_PASSWORD));

        assertThat(expired.status()).isEqualTo(400);
        assertThat(expired.code()).isEqualTo("INVALID_TOKEN");
        assertThat(userRow(invitee.id()).get("status")).isEqualTo("INVITED");
    }

    @Test
    void resendingAnInvitationRetiresTheOldLink() {
        TestUser invitee = invitee(UserRole.COMMUNITY_ADMIN);
        invite(invitee);
        String first = tokenFromLatestEmail("invitation", invitee.email());
        invite(invitee);
        String second = tokenFromLatestEmail("invitation", invitee.email());

        assertThat(api.post("/api/v1/auth/accept-invite", Map.of("token", first, "newPassword", NEW_PASSWORD)).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/auth/accept-invite", Map.of("token", second, "newPassword", NEW_PASSWORD)).status()).isEqualTo(200);
    }

    @Test
    void aPasswordResetTokenCannotAcceptAnInvitationAndViceVersa() {
        TestUser admin = users.communityAdmin();
        forgot(admin.email());
        String resetToken = tokenFromLatestEmail("password-reset", admin.email());

        assertThat(api.post("/api/v1/auth/accept-invite", Map.of("token", resetToken, "newPassword", NEW_PASSWORD)).status()).isEqualTo(400);

        TestUser invitee = invitee(UserRole.COMMUNITY_ADMIN);
        invite(invitee);
        String inviteToken = tokenFromLatestEmail("invitation", invitee.email());
        assertThat(reset(inviteToken, NEW_PASSWORD).status()).isEqualTo(400);
    }


}

package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.auth.crypto.TotpSecretCipher;
import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.AuthTestUsers.TestUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TwoFactorIT extends AbstractAuthIT {

    @Autowired TotpService totp;
    @Autowired TotpSecretCipher cipher;

    private record Enrolled(String secret, List<String> recoveryCodes) {}

    private Enrolled enroll(TestUser user) {
        Session session = loginOk(user);
        String secret = api.post("/api/v1/auth/2fa/setup", null, "Authorization", session.bearer()).json().get("secret").asString();
        ApiClient.Response enable = api.post("/api/v1/auth/2fa/enable", Map.of("code", code(secret)), "Authorization", session.bearer());
        assertThat(enable.status()).as(enable.body()).isEqualTo(200);
        List<String> codes = enable.json().get("recoveryCodes").valueStream().map(n -> n.asString()).toList();
        return new Enrolled(secret, codes);
    }

    private String code(String secret) {
        return totp.codeAt(secret, Instant.now());
    }

    /** Test-only: forget the last accepted step so the same 30-second code can be used again. */
    private void forgetReplayGuard(UUID userId) {
        jdbc.update("update users set totp_last_used_step = null where id = ?", userId);
    }

    private String mfaToken(TestUser user) {
        ApiClient.Response response = login(user);
        assertThat(response.json().get("mfaRequired").asBoolean()).isTrue();
        return response.json().get("mfaToken").asString();
    }

    private ApiClient.Response secondFactor(String mfaToken, Map<String, String> factor) {
        java.util.Map<String, String> body = new java.util.HashMap<>(factor);
        body.put("mfaToken", mfaToken);
        return api.post("/api/v1/auth/login/2fa", body);
    }

    // ---- enrolment --------------------------------------------------------------------------

    @Test
    void setupReturnsSecretAndOtpAuthUriAndStoresTheSecretEncrypted() {
        TestUser admin = users.communityAdmin();
        Session session = loginOk(admin);

        ApiClient.Response response = api.post("/api/v1/auth/2fa/setup", null, "Authorization", session.bearer());

        assertThat(response.status()).isEqualTo(200);
        String secret = response.json().get("secret").asString();
        String uri = response.json().get("otpauthUri").asString();
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(uri).startsWith("otpauth://totp/").contains("secret=" + secret).contains("issuer=Amanah").contains("digits=6").contains("period=30");

        Map<String, Object> row = userRow(admin.id());
        String stored = (String) row.get("totp_secret_enc");
        assertThat(stored).isNotBlank().doesNotContain(secret);
        assertThat(cipher.decrypt(stored, admin.id().toString())).as("AES-GCM round trip").isEqualTo(secret);
        assertThat(row.get("totp_enabled")).as("not active until the first code is verified").isEqualTo(false);
        assertThat(auditCount(admin.id(), "TWO_FACTOR_SETUP_STARTED")).isEqualTo(1);
    }

    @Test
    void theEncryptedSecretIsBoundToItsUser() {
        TestUser a = users.communityAdmin();
        TestUser b = users.communityAdmin();
        Session session = loginOk(a);
        api.post("/api/v1/auth/2fa/setup", null, "Authorization", session.bearer());
        String stored = (String) userRow(a.id()).get("totp_secret_enc");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cipher.decrypt(stored, b.id().toString()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enableRejectsAWrongCodeAndAcceptsTheRightOneReturningTenRecoveryCodes() {
        TestUser admin = users.communityAdmin();
        Session session = loginOk(admin);
        String secret = api.post("/api/v1/auth/2fa/setup", null, "Authorization", session.bearer()).json().get("secret").asString();

        ApiClient.Response wrong = api.post("/api/v1/auth/2fa/enable", Map.of("code", "000000"), "Authorization", session.bearer());
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.code()).isEqualTo("INVALID_MFA_CODE");
        assertThat(userRow(admin.id()).get("totp_enabled")).isEqualTo(false);

        ApiClient.Response ok = api.post("/api/v1/auth/2fa/enable", Map.of("code", code(secret)), "Authorization", session.bearer());
        assertThat(ok.status()).isEqualTo(200);
        List<String> codes = ok.json().get("recoveryCodes").valueStream().map(n -> n.asString()).toList();
        assertThat(codes).hasSize(10).doesNotHaveDuplicates().allMatch(c -> c.matches("[A-Z2-9]{6}-[A-Z2-9]{6}"));
        assertThat(userRow(admin.id()).get("totp_enabled")).isEqualTo(true);

        List<String> stored = jdbc.queryForList("select code_hash from recovery_codes where user_id = ?", String.class, admin.id());
        assertThat(stored).hasSize(10).allMatch(h -> h.matches("[0-9a-f]{64}"));
        assertThat(stored).as("only hashes are stored").doesNotContainAnyElementsOf(codes);
        assertThat(auditCount(admin.id(), "TWO_FACTOR_ENABLED")).isEqualTo(1);
    }

    @Test
    void enableWithoutSetupAndSetupWhenAlreadyEnabledAreConflicts() {
        TestUser admin = users.communityAdmin();
        Session session = loginOk(admin);
        ApiClient.Response noSetup = api.post("/api/v1/auth/2fa/enable", Map.of("code", "123456"), "Authorization", session.bearer());
        assertThat(noSetup.status()).isEqualTo(409);
        assertThat(noSetup.code()).isEqualTo("MFA_SETUP_NOT_STARTED");

        enroll(admin);
        Session again = new Session(session.accessToken(), null);
        ApiClient.Response setupAgain = api.post("/api/v1/auth/2fa/setup", null, "Authorization", again.bearer());
        assertThat(setupAgain.status()).isEqualTo(409);
        assertThat(setupAgain.code()).isEqualTo("MFA_ALREADY_ENABLED");
    }

    @Test
    void enableRejectsMalformedCodes() {
        Session session = loginOk(users.communityAdmin());
        api.post("/api/v1/auth/2fa/setup", null, "Authorization", session.bearer());

        assertThat(api.post("/api/v1/auth/2fa/enable", Map.of("code", "12345"), "Authorization", session.bearer()).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/auth/2fa/enable", Map.of("code", "abcdef"), "Authorization", session.bearer()).status()).isEqualTo(400);
    }

    // ---- login with 2FA ---------------------------------------------------------------------

    @Test
    void loginWithTwoFactorReturnsAShortLivedChallengeAndNoSession() throws Exception {
        TestUser admin = users.communityAdmin();
        enroll(admin);

        ApiClient.Response response = login(admin);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("mfaRequired").asBoolean()).isTrue();
        assertThat(response.json().has("accessToken")).isFalse();
        assertThat(response.rawSetCookie("amanah_refresh")).as("no session yet").isNull();
        com.nimbusds.jwt.JWTClaimsSet claims = com.nimbusds.jwt.SignedJWT.parse(response.json().get("mfaToken").asString()).getJWTClaimsSet();
        assertThat(claims.getStringClaim("typ")).isEqualTo("mfa");
        assertThat(java.time.Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant())).isEqualTo(java.time.Duration.ofMinutes(5));
        assertThat(response.json().get("expiresIn").asInt()).isEqualTo(300);
    }

    @Test
    void theMfaTokenCannotBeUsedAsAnAccessToken() {
        TestUser admin = users.communityAdmin();
        enroll(admin);
        String mfa = mfaToken(admin);

        assertThat(api.get("/api/v1/auth/me", "Authorization", ApiClient.bearer(mfa)).status()).isEqualTo(401);
    }

    @Test
    void anAccessTokenCannotCompleteAnMfaChallenge() {
        TestUser admin = users.communityAdmin();
        Enrolled enrolled = enroll(admin);
        Session session = loginOkAfterMfa(admin, enrolled);

        ApiClient.Response response = secondFactor(session.accessToken(), Map.of("code", code(enrolled.secret())));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("INVALID_TOKEN");
    }

    private Session loginOkAfterMfa(TestUser admin, Enrolled enrolled) {
        forgetReplayGuard(admin.id());
        ApiClient.Response done = secondFactor(mfaToken(admin), Map.of("code", code(enrolled.secret())));
        assertThat(done.status()).as(done.body()).isEqualTo(200);
        return new Session(done.json().get("accessToken").asString(), done.cookie("amanah_refresh"));
    }

    @Test
    void aValidTotpCodeCompletesLoginAndIssuesTheCookie() {
        TestUser admin = users.communityAdmin();
        Enrolled enrolled = enroll(admin);
        forgetReplayGuard(admin.id());

        ApiClient.Response done = secondFactor(mfaToken(admin), Map.of("code", code(enrolled.secret())));

        assertThat(done.status()).isEqualTo(200);
        assertThat(done.json().get("accessToken").asString()).isNotBlank();
        assertThat(done.rawSetCookie("amanah_refresh")).contains("HttpOnly", "Secure", "SameSite=Strict");
        assertThat(refresh(done.cookie("amanah_refresh")).status()).isEqualTo(200);
        assertThat(auditCount(admin.id(), "LOGIN_SUCCESS")).isGreaterThanOrEqualTo(2); // enrolment login + this one
    }

    @Test
    void aTotpCodeCannotBeReplayed() {
        TestUser admin = users.communityAdmin();
        Enrolled enrolled = enroll(admin);
        forgetReplayGuard(admin.id());
        String code = code(enrolled.secret());

        ApiClient.Response first = secondFactor(mfaToken(admin), Map.of("code", code));
        ApiClient.Response replay = secondFactor(mfaToken(admin), Map.of("code", code));

        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.code()).isEqualTo("INVALID_MFA_CODE");
    }

    @Test
    void theCodeUsedToEnableCannotBeReusedToLogIn() {
        TestUser admin = users.communityAdmin();
        Enrolled enrolled = enroll(admin); // enable consumed this 30-second step

        ApiClient.Response response = secondFactor(mfaToken(admin), Map.of("code", code(enrolled.secret())));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void wrongCodesCountTowardLockout() {
        TestUser admin = users.communityAdmin();
        Enrolled enrolled = enroll(admin);
        forgetReplayGuard(admin.id());
        String mfa = mfaToken(admin);

        for (int i = 0; i < 5; i++) {
            assertThat(secondFactor(mfa, Map.of("code", "000000")).status()).isEqualTo(401);
        }
        ApiClient.Response locked = secondFactor(mfa, Map.of("code", code(enrolled.secret())));

        assertThat(locked.status()).isEqualTo(423);
        assertThat(locked.code()).isEqualTo("ACCOUNT_LOCKED");
        assertThat(auditCount(admin.id(), "MFA_FAILED")).isEqualTo(5);
        assertThat(login(admin).status()).as("the password step is locked too").isEqualTo(423);
    }

    @Test
    void anExpiredOrTamperedMfaTokenIsRejected() {
        TestUser admin = users.communityAdmin();
        Enrolled enrolled = enroll(admin);
        String mfa = mfaToken(admin);
        String tampered = mfa.substring(0, mfa.length() - 3) + (mfa.endsWith("AAA") ? "BBB" : "AAA");

        ApiClient.Response response = secondFactor(tampered, Map.of("code", code(enrolled.secret())));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void loginRequiresACodeOrARecoveryCode() {
        TestUser admin = users.communityAdmin();
        enroll(admin);

        ApiClient.Response response = secondFactor(mfaToken(admin), Map.of());

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("INVALID_MFA_CODE");
    }

    // ---- recovery codes ---------------------------------------------------------------------

    @Test
    void aRecoveryCodeWorksExactlyOnce() {
        TestUser admin = users.communityAdmin();
        Enrolled enrolled = enroll(admin);
        String recovery = enrolled.recoveryCodes().get(0);

        ApiClient.Response first = secondFactor(mfaToken(admin), Map.of("recoveryCode", recovery.toLowerCase())); // case/hyphen tolerant
        ApiClient.Response second = secondFactor(mfaToken(admin), Map.of("recoveryCode", recovery));
        ApiClient.Response other = secondFactor(mfaToken(admin), Map.of("recoveryCode", enrolled.recoveryCodes().get(1).replace("-", "")));

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(401);
        assertThat(second.code()).isEqualTo("INVALID_MFA_CODE");
        assertThat(other.status()).as("a different code still works").isEqualTo(200);
        Long used = jdbc.queryForObject("select count(*) from recovery_codes where user_id = ? and used_at is not null", Long.class, admin.id());
        assertThat(used).isEqualTo(2);
        assertThat(auditCount(admin.id(), "RECOVERY_CODE_USED")).isEqualTo(2);
    }

    @Test
    void aWrongRecoveryCodeFails() {
        TestUser admin = users.communityAdmin();
        enroll(admin);

        ApiClient.Response response = secondFactor(mfaToken(admin), Map.of("recoveryCode", "AAAAAA-AAAAAA"));

        assertThat(response.status()).isEqualTo(401);
    }

    // ---- disable ----------------------------------------------------------------------------

    @Test
    void disableNeedsThePasswordAndACode() {
        TestUser admin = users.communityAdmin();
        Enrolled enrolled = enroll(admin);
        Session session = loginOkAfterMfa(admin, enrolled);
        forgetReplayGuard(admin.id());

        ApiClient.Response wrongPassword = api.post("/api/v1/auth/2fa/disable",
                Map.of("password", "Wrong-Password-1", "code", code(enrolled.secret())), "Authorization", session.bearer());
        ApiClient.Response wrongCode = api.post("/api/v1/auth/2fa/disable",
                Map.of("password", admin.password(), "code", "000000"), "Authorization", session.bearer());
        assertThat(wrongPassword.status()).isEqualTo(403);
        assertThat(wrongPassword.code()).isEqualTo("INVALID_CURRENT_PASSWORD");
        assertThat(wrongCode.status()).isEqualTo(401);
        assertThat(userRow(admin.id()).get("totp_enabled")).as("still on").isEqualTo(true);

        ApiClient.Response ok = api.post("/api/v1/auth/2fa/disable",
                Map.of("password", admin.password(), "code", code(enrolled.secret())), "Authorization", session.bearer());

        assertThat(ok.status()).isEqualTo(204);
        Map<String, Object> row = userRow(admin.id());
        assertThat(row.get("totp_enabled")).isEqualTo(false);
        assertThat(row.get("totp_secret_enc")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from recovery_codes where user_id = ?", Long.class, admin.id())).isZero();
        assertThat(login(admin).json().has("accessToken")).as("plain login again").isTrue();
        assertThat(auditCount(admin.id(), "TWO_FACTOR_DISABLED")).isEqualTo(1);
    }

    @Test
    void disableWhenNotEnabledIsAConflict() {
        Session session = loginOk(users.communityAdmin());

        ApiClient.Response response = api.post("/api/v1/auth/2fa/disable", Map.of("password", AuthTestUsersPassword.VALUE, "code", "123456"), "Authorization", session.bearer());

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("MFA_NOT_ENABLED");
    }

    private static final class AuthTestUsersPassword {
        static final String VALUE = com.amanahconnect.support.AuthTestUsers.PASSWORD;
    }

    // ---- mandatory 2FA ----------------------------------------------------------------------

    @Test
    void aSuperAdminWithoutTwoFactorGetsARestrictedToken() throws Exception {
        TestUser superAdmin = users.superAdmin();

        ApiClient.Response response = login(superAdmin);
        String token = response.json().get("accessToken").asString();
        String bearer = ApiClient.bearer(token);

        assertThat(response.json().get("mfaSetupRequired").asBoolean()).isTrue();
        assertThat(com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet().getBooleanClaim("mfa_setup_required")).isTrue();

        assertThat(api.get("/api/v1/auth/me", "Authorization", bearer).status()).as("state is readable").isEqualTo(200);
        assertThat(api.post("/api/v1/auth/2fa/setup", null, "Authorization", bearer).status()).as("setup allowed").isEqualTo(200);

        ApiClient.Response blockedAdmin = api.get("/api/v1/admin/communities", "Authorization", bearer);
        ApiClient.Response blockedOther = api.post("/api/v1/auth/password/change", Map.of("currentPassword", "x", "newPassword", "y"), "Authorization", bearer);
        assertThat(blockedAdmin.status()).isEqualTo(403);
        assertThat(blockedAdmin.code()).isEqualTo("MFA_SETUP_REQUIRED");
        assertThat(blockedOther.status()).isEqualTo(403);
        assertThat(blockedOther.code()).isEqualTo("MFA_SETUP_REQUIRED");
    }

    @Test
    void finishingEnrolmentAndRefreshingLiftsTheRestriction() {
        TestUser superAdmin = users.superAdmin();
        Session session = loginOk(superAdmin);
        String secret = api.post("/api/v1/auth/2fa/setup", null, "Authorization", session.bearer()).json().get("secret").asString();
        api.post("/api/v1/auth/2fa/enable", Map.of("code", code(secret)), "Authorization", session.bearer());

        String fresh = refresh(session.refreshToken()).json().get("accessToken").asString();

        assertThat(api.get("/api/v1/auth/me", "Authorization", ApiClient.bearer(fresh)).json().get("flags").get("mfaSetupRequired").asBoolean()).isFalse();
        // authorised now: the request passes security and reaches the admin controller
        assertThat(api.get("/api/v1/admin/communities", "Authorization", ApiClient.bearer(fresh)).status()).isEqualTo(200);
    }

    @Test
    void aSuperAdminCannotTurnTwoFactorOff() {
        TestUser superAdmin = users.superAdmin();
        Enrolled enrolled = enroll(superAdmin);
        Session session = loginOkAfterMfa(superAdmin, enrolled);
        forgetReplayGuard(superAdmin.id());

        ApiClient.Response response = api.post("/api/v1/auth/2fa/disable",
                Map.of("password", superAdmin.password(), "code", code(enrolled.secret())), "Authorization", session.bearer());

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("MFA_REQUIRED_BY_POLICY");
        assertThat(userRow(superAdmin.id()).get("totp_enabled")).isEqualTo(true);
    }

    @Test
    void aCommunityCanRequireTwoFactorOfItsAdmins() {
        com.amanahconnect.community.Community community = communityRequiring2fa();
        TestUser admin = users.communityAdminOf(community);

        ApiClient.Response response = login(admin);

        assertThat(response.json().get("mfaSetupRequired").asBoolean()).isTrue();
        assertThat(api.get("/api/v1/community/members", "Authorization", ApiClient.bearer(response.json().get("accessToken").asString())).code()).isEqualTo("MFA_SETUP_REQUIRED");
        ApiClient.Response me = api.get("/api/v1/auth/me", "Authorization", ApiClient.bearer(response.json().get("accessToken").asString()));
        assertThat(me.json().get("flags").get("mfaMandatory").asBoolean()).isTrue();
    }

    @Autowired com.amanahconnect.support.TestData data;
    @Autowired com.amanahconnect.community.CommunityRepository communities;

    private com.amanahconnect.community.Community communityRequiring2fa() {
        com.amanahconnect.community.Community community = data.community();
        community.setSettings(Map.of("require_2fa", true));
        return communities.save(community);
    }

    @Test
    void secretsAndCodesNeverReachTheAuditLog() {
        TestUser admin = users.communityAdmin();
        Enrolled enrolled = enroll(admin);
        forgetReplayGuard(admin.id());
        secondFactor(mfaToken(admin), Map.of("recoveryCode", enrolled.recoveryCodes().get(0)));

        String audit = jdbc.queryForList(
                "select coalesce(before::text,'') || coalesce(after::text,'') from audit_logs where actor_user_id = ?", String.class, admin.id()).toString();

        assertThat(audit).doesNotContain(enrolled.secret());
        for (String recovery : enrolled.recoveryCodes()) {
            assertThat(audit).doesNotContain(recovery).doesNotContain(recovery.replace("-", ""));
        }
    }
}

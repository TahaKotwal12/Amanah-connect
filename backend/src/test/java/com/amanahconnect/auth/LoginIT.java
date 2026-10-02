package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.AuthTestUsers.TestUser;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.amanahconnect.auth.UserRole;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoginIT extends AbstractAuthIT {

    @Test
    void successReturnsAccessTokenInBodyAndHardenedRefreshCookie() throws Exception {
        TestUser admin = users.communityAdmin();

        ApiClient.Response response = login(admin);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(response.json().get("expiresIn").asInt()).isEqualTo(900);
        assertThat(response.json().get("mfaSetupRequired").asBoolean()).isFalse();
        assertThat(response.body()).as("the refresh token never appears in the body").doesNotContain("refresh");

        String cookie = response.rawSetCookie("amanah_refresh");
        assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api/v1/auth").contains("Max-Age=604800");
        assertThat(response.cookie("amanah_refresh")).hasSize(43); // 256 bits, base64url

        JWTClaimsSet claims = SignedJWT.parse(response.json().get("accessToken").asString()).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(admin.id().toString());
        assertThat(claims.getStringClaim("role")).isEqualTo("COMMUNITY_ADMIN");
        assertThat(claims.getStringClaim("typ")).isEqualTo("access");
        assertThat(claims.getBooleanClaim("mfa_setup_required")).isFalse();
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(claims.getJWTID()).isNotBlank();
    }

    @Test
    void accessTokenAuthenticatesRequests() {
        TestUser admin = users.communityAdmin();
        Session session = loginOk(admin);

        ApiClient.Response me = api.get("/api/v1/auth/me", "Authorization", session.bearer());

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().get("user").get("email").asString()).isEqualToIgnoringCase(admin.email());
        assertThat(me.json().get("user").get("role").asString()).isEqualTo("COMMUNITY_ADMIN");
        assertThat(me.json().get("community").get("slug").asString()).startsWith("c-");
        assertThat(me.json().get("flags").get("totpEnabled").asBoolean()).isFalse();
        assertThat(me.body()).doesNotContain("password").doesNotContain("totp_secret");
    }

    @Test
    void emailMatchIsCaseInsensitiveAndTrimmed() {
        TestUser admin = users.communityAdmin();

        ApiClient.Response response =
                api.post("/api/v1/auth/login", Map.of("email", "  " + admin.email().toUpperCase() + " ", "password", admin.password()));

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void unknownEmailAndWrongPasswordGiveIdenticalResponses() {
        TestUser admin = users.communityAdmin();

        ApiClient.Response wrongPassword =
                api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", "Wrong-Password-1"));
        ApiClient.Response unknownEmail =
                api.post("/api/v1/auth/login", Map.of("email", "nobody-" + admin.id() + "@example.test", "password", "Wrong-Password-1"));

        assertThat(wrongPassword.status()).isEqualTo(401).isEqualTo(unknownEmail.status());
        assertThat(wrongPassword.code()).isEqualTo("INVALID_CREDENTIALS").isEqualTo(unknownEmail.code());
        assertThat(wrongPassword.json().get("title")).isEqualTo(unknownEmail.json().get("title"));
        assertThat(wrongPassword.json().get("detail")).isEqualTo(unknownEmail.json().get("detail"));
        assertThat(wrongPassword.json().get("type")).isEqualTo(unknownEmail.json().get("type"));
        assertThat(wrongPassword.header("Content-Type")).isEqualTo(unknownEmail.header("Content-Type"));
        assertThat(wrongPassword.rawSetCookie("amanah_refresh")).isNull();
    }

    @Test
    void fiveFailuresLockTheAccountForFifteenMinutes() {
        TestUser admin = users.communityAdmin();

        for (int i = 1; i <= 5; i++) {
            ApiClient.Response failed =
                    api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", "Wrong-Password-" + i));
            assertThat(failed.status()).as("attempt %d", i).isEqualTo(401);
        }

        Map<String, Object> row = userRow(admin.id());
        assertThat(((Number) row.get("failed_attempts")).intValue()).isEqualTo(5);
        Instant lockedUntil = ((Timestamp) row.get("locked_until")).toInstant();
        assertThat(Duration.between(Instant.now(), lockedUntil)).isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15).plusSeconds(5));

        ApiClient.Response withCorrectPassword = login(admin);
        assertThat(withCorrectPassword.status()).isEqualTo(423);
        assertThat(withCorrectPassword.code()).isEqualTo("ACCOUNT_LOCKED");
        assertThat(auditCount(admin.id(), "ACCOUNT_LOCKED")).isEqualTo(1);
        assertThat(auditCount(admin.id(), "LOGIN_FAILED")).isEqualTo(5);
    }

    @Test
    void fourFailuresDoNotLock() {
        TestUser admin = users.communityAdmin();
        for (int i = 0; i < 4; i++) {
            api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", "Wrong-Password-" + i));
        }

        assertThat(login(admin).status()).isEqualTo(200);
        assertThat(((Number) userRow(admin.id()).get("failed_attempts")).intValue()).as("success resets the counter").isZero();
    }

    @Test
    void lockEndsAfterFifteenMinutes() {
        TestUser admin = users.communityAdmin();
        for (int i = 0; i < 5; i++) {
            api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", "Wrong-Password-" + i));
        }
        assertThat(login(admin).status()).isEqualTo(423);

        jdbc.update("update users set locked_until = now() - interval '1 minute' where id = ?", admin.id());

        assertThat(login(admin).status()).isEqualTo(200);
        Map<String, Object> row = userRow(admin.id());
        assertThat(((Number) row.get("failed_attempts")).intValue()).isZero();
        assertThat(row.get("locked_until")).isNull();
    }

    @Test
    void anExpiredLockStartsAFreshCountInsteadOfLockingAtOnce() {
        TestUser admin = users.communityAdmin();
        jdbc.update("update users set failed_attempts = 5, locked_until = now() - interval '1 minute' where id = ?", admin.id());

        ApiClient.Response wrong = api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", "Wrong-Password-1"));

        assertThat(wrong.status()).isEqualTo(401);
        assertThat(((Number) userRow(admin.id()).get("failed_attempts")).intValue()).isEqualTo(1);
    }

    @Test
    void unknownEmailsLockOutLikeRealOnesSoLockoutRevealsNothing() {
        String email = "ghost-" + System.nanoTime() + "@example.test";

        for (int i = 0; i < 5; i++) {
            ApiClient.Response failed = api.post("/api/v1/auth/login", Map.of("email", email, "password", "Wrong-Password-" + i));
            assertThat(failed.code()).isEqualTo("INVALID_CREDENTIALS");
        }
        ApiClient.Response sixth = api.post("/api/v1/auth/login", Map.of("email", email, "password", "Wrong-Password-6"));

        assertThat(sixth.status()).as("same lockout behaviour as a real account").isEqualTo(423);
        assertThat(sixth.code()).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void disabledAndInvitedAccountsCannotSignIn() {
        TestUser disabled = users.create(UserRole.COMMUNITY_ADMIN, UserStatus.DISABLED);
        TestUser invited = users.create(UserRole.COMMUNITY_ADMIN, UserStatus.INVITED);

        ApiClient.Response disabledLogin = login(disabled);
        ApiClient.Response invitedLogin = login(invited);

        assertThat(disabledLogin.status()).isEqualTo(401);
        assertThat(disabledLogin.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(invitedLogin.status()).isEqualTo(401);
        assertThat(invitedLogin.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void successAndFailureAreAuditedWithoutSecrets() {
        TestUser admin = users.communityAdmin();
        String wrong = "Wrong-Password-Audit-1";
        api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", wrong));
        Session session = loginOk(admin);

        List<Map<String, Object>> success = auditRows(admin.id(), "LOGIN_SUCCESS");
        List<Map<String, Object>> failed = auditRows(admin.id(), "LOGIN_FAILED");

        assertThat(success).hasSize(1);
        assertThat(failed).hasSize(1);
        assertThat(success.get(0).get("community_id")).as("community recorded for community admins").isNotNull();
        assertThat(success.get(0).get("ip")).isNotNull();
        assertThat(success.get(0).get("request_id")).isNotNull();
        String everything = jdbc.queryForList("select coalesce(before::text,'') || coalesce(after::text,'') from audit_logs where actor_user_id = ?", String.class, admin.id()).toString();
        assertThat(everything).doesNotContain(wrong).doesNotContain(admin.password()).doesNotContain(session.accessToken()).doesNotContain(session.refreshToken());
    }

    @Test
    void unknownEmailAttemptsAreAuditedWithTheAddressMasked() {
        String email = "stranger-" + System.nanoTime() + "@example.test";
        api.post("/api/v1/auth/login", Map.of("email", email, "password", "Wrong-Password-1"));

        List<String> rows =
                jdbc.queryForList(
                        "select after::text from audit_logs where actor_user_id is null and action = 'LOGIN_FAILED' and after->>'attemptedEmail' = ?",
                        String.class,
                        "s***@example.test");

        assertThat(rows).isNotEmpty();
        assertThat(rows.toString()).doesNotContain(email);
    }

    @Test
    void invalidBodiesAreRejectedWithoutDetail() {
        ApiClient.Response blank = api.post("/api/v1/auth/login", Map.of("email", "", "password", ""));
        ApiClient.Response notJson = api.postRaw("/api/v1/auth/login", "not json", "Content-Type", "application/json");

        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(notJson.status()).isEqualTo(400);
        assertThat(notJson.body()).doesNotContain("Exception").doesNotContain("at com.");
    }

    @Test
    void aStaleBearerTokenOnAPublicEndpointIsIgnored() {
        TestUser admin = users.communityAdmin();

        ApiClient.Response response =
                api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", admin.password()), "Authorization", "Bearer garbage.token.value");

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void errorsAreProblemJsonWithRequestId() {
        ApiClient.Response response =
                api.post("/api/v1/auth/login", Map.of("email", "x@example.test", "password", "nope"), "X-Request-Id", "login-req-1");

        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        assertThat(response.json().get("requestId").asString()).isEqualTo("login-req-1");
        assertThat(response.json().propertyNames()).contains("type", "title", "status", "detail", "code");
    }

}

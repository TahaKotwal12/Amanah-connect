package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.auth.crypto.AuthKeys;
import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.AuthTestUsers.TestUser;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Who may call what, and which forged or stale tokens are refused. */
class RoleAuthorizationIT extends AbstractAuthIT {

    @Autowired JwtService jwt;
    @Autowired AuthProperties properties;
    @Autowired AuthKeys keys;
    @Autowired UserRepository userRepository;

    private String accessToken(TestUser user, boolean mfaSetupRequired) {
        return jwt.issueAccessToken(userRepository.findById(user.id()).orElseThrow(), mfaSetupRequired);
    }

    private ApiClient.Response get(String path, String token) {
        return api.get(path, "Authorization", ApiClient.bearer(token));
    }

    // ---- who may call what ------------------------------------------------------------------

    @Test
    void everythingExceptTheDocumentedPublicEndpointsNeedsAToken() {
        for (String path : List.of("/api/v1/admin/communities", "/api/v1/community/members", "/api/v1/auth/me", "/api/v1/anything-else")) {
            ApiClient.Response response = api.get(path);
            assertThat(response.status()).as(path).isEqualTo(401);
            assertThat(response.code()).as(path).isEqualTo("UNAUTHENTICATED");
            assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
        }
        assertThat(api.post("/api/v1/auth/2fa/setup", null).status()).isEqualTo(401);
        assertThat(api.post("/api/v1/auth/logout-all", null).status()).isEqualTo(401);
    }

    @Test
    void aCommunityAdminTokenIsRejectedOnAdminEndpoints() {
        String token = accessToken(users.communityAdmin(), false);

        ApiClient.Response admin = get("/api/v1/admin/communities", token);
        ApiClient.Response adminWrite = api.post("/api/v1/admin/communities", java.util.Map.of(), "Authorization", ApiClient.bearer(token));

        assertThat(admin.status()).isEqualTo(403);
        assertThat(admin.code()).isEqualTo("FORBIDDEN");
        assertThat(adminWrite.status()).isEqualTo(403);
    }

    @Test
    void aCommunityAdminTokenPassesSecurityOnCommunityEndpoints() {
        String token = accessToken(users.communityAdmin(), false);

        // No /community controller exists yet, so a request that gets past security ends in 404, not 401/403.
        ApiClient.Response response = get("/api/v1/community/members", token);

        assertThat(response.status()).isEqualTo(404);
        assertThat(get("/api/v1/auth/me", token).status()).isEqualTo(200);
    }

    @Test
    void aSuperAdminTokenIsRejectedOnCommunityEndpointsButAllowedOnAdminEndpoints() {
        String token = accessToken(users.superAdmin(), false);

        ApiClient.Response community = get("/api/v1/community/members", token);
        ApiClient.Response admin = get("/api/v1/admin/communities", token);

        assertThat(community.status()).as("a super admin has no community of their own").isEqualTo(403);
        assertThat(community.code()).isEqualTo("FORBIDDEN");
        assertThat(admin.status()).as("passes security and reaches the controller").isEqualTo(200);
    }

    @Test
    void aTokenStillRestrictedByMandatoryTwoFactorCannotReachAnythingButEnrolment() {
        String token = accessToken(users.superAdmin(), true);

        assertThat(get("/api/v1/admin/communities", token).code()).isEqualTo("MFA_SETUP_REQUIRED");
        assertThat(get("/api/v1/community/members", token).status()).isEqualTo(403);
        assertThat(get("/api/v1/auth/me", token).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/auth/2fa/setup", null, "Authorization", ApiClient.bearer(token)).status()).isEqualTo(200);
    }

    // ---- forged and stale tokens ------------------------------------------------------------

    @Test
    void forgedAndStaleTokensAreAllRefusedWith401() {
        TestUser admin = users.communityAdmin();
        User entity = userRepository.findById(admin.id()).orElseThrow();
        String valid = jwt.issueAccessToken(entity, false);

        String[] parts = valid.split("\\.");
        String tamperedPayload = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("COMMUNITY_ADMIN", "SUPER_ADMIN").getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        String badSignature = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);

        byte[] otherSecret = new byte[48];
        new SecureRandom().nextBytes(otherSecret);
        JwtService foreign = new JwtService(properties, new AuthKeys(new SecretKeySpec(otherSecret, "HmacSHA256"), keys.totpKey()), Clock.systemUTC());

        JwtService longAgo = new JwtService(properties, keys, Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));

        String unsigned = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "."
                + b64("{\"sub\":\"" + admin.id() + "\",\"role\":\"SUPER_ADMIN\",\"typ\":\"access\",\"iss\":\"amanah-connect\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}") + ".";

        List<String> bad = List.of(
                "garbage",
                "a.b.c",
                tamperedPayload,
                badSignature,
                longAgo.issueAccessToken(entity, false),
                foreign.issueAccessToken(entity, false),
                unsigned,
                jwt.issueMfaToken(entity));

        for (String token : bad) {
            ApiClient.Response response = get("/api/v1/auth/me", token);
            assertThat(response.status()).as("token %s...", token.substring(0, Math.min(20, token.length()))).isEqualTo(401);
            assertThat(response.header("Content-Type")).startsWith("application/problem+json");
            assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
            assertThat(response.body()).as("no parser internals leak").doesNotContain("Jwt").doesNotContain("nimbus");
        }
        assertThat(get("/api/v1/auth/me", valid).status()).as("the untouched original is fine").isEqualTo(200);
    }

    @Test
    void aStaleBearerHeaderNeverBreaksPublicEndpoints() {
        ApiClient.Response ping = api.get("/api/v1/ping", "Authorization", "Bearer garbage");
        ApiClient.Response health = api.get("/actuator/health/readiness", "Authorization", "Bearer garbage");

        assertThat(ping.status()).isEqualTo(200);
        assertThat(health.status()).isEqualTo(200);
    }

    @Test
    void unauthorisedResponsesCarryTheRequestId() {
        ApiClient.Response response = api.get("/api/v1/admin/communities", "X-Request-Id", "authz-1");

        assertThat(response.header("X-Request-Id")).isEqualTo("authz-1");
        assertThat(response.json().get("requestId").asString()).isEqualTo("authz-1");
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}

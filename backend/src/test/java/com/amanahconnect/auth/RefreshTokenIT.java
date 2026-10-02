package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.AuthTestUsers.TestUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RefreshTokenIT extends AbstractAuthIT {

    @Test
    void refreshRotatesTheTokenAndIssuesANewAccessToken() {
        TestUser admin = users.communityAdmin();
        Session first = loginOk(admin);

        ApiClient.Response response = refresh(first.refreshToken());

        assertThat(response.status()).isEqualTo(200);
        String second = response.cookie("amanah_refresh");
        assertThat(second).isNotBlank().isNotEqualTo(first.refreshToken());
        assertThat(response.json().get("accessToken").asString()).isNotBlank();
        assertThat(response.rawSetCookie("amanah_refresh")).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth");

        Map<String, Object> oldRow = jdbc.queryForMap("select * from refresh_tokens where token_hash = ?", hash(first.refreshToken()));
        Map<String, Object> newRow = jdbc.queryForMap("select * from refresh_tokens where token_hash = ?", hash(second));
        assertThat(oldRow.get("revoked_at")).as("the used token is revoked").isNotNull();
        assertThat(oldRow.get("replaced_by")).isEqualTo(newRow.get("id"));
        assertThat(oldRow.get("family_id")).as("same family").isEqualTo(newRow.get("family_id"));
        assertThat(newRow.get("revoked_at")).isNull();

        // the new token keeps working, so the chain continues
        assertThat(refresh(second).status()).isEqualTo(200);
    }

    @Test
    void onlyHashesAreStoredNeverTheToken() {
        TestUser admin = users.communityAdmin();
        Session session = loginOk(admin);

        Long raw = jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Long.class, session.refreshToken());
        Long hashed = jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Long.class, hash(session.refreshToken()));

        assertThat(raw).isZero();
        assertThat(hashed).isOne();
        assertThat(jdbc.queryForObject("select token_hash ~ '^[0-9a-f]{64}$' from refresh_tokens where token_hash = ?", Boolean.class, hash(session.refreshToken()))).isTrue();
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() {
        TestUser admin = users.communityAdmin();
        Session first = loginOk(admin);
        String second = refresh(first.refreshToken()).cookie("amanah_refresh");

        ApiClient.Response replay = refresh(first.refreshToken()); // an attacker replays the stolen, already-used token

        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.code()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(replay.rawSetCookie("amanah_refresh")).as("the dead cookie is cleared").contains("Max-Age=0");
        assertThat(auditCount(admin.id(), "TOKEN_REUSE_DETECTED")).isEqualTo(1);
        assertThat(refresh(second).status()).as("the legitimate newer token is revoked too").isEqualTo(401);
        Long stillActive = jdbc.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Long.class, admin.id());
        assertThat(stillActive).isZero();
    }

    @Test
    void reuseInOneFamilyDoesNotAffectAnotherDevice() {
        TestUser admin = users.communityAdmin();
        Session laptop = loginOk(admin);
        Session phone = loginOk(admin);
        String laptopNext = refresh(laptop.refreshToken()).cookie("amanah_refresh");
        refresh(laptop.refreshToken()); // replay -> laptop family revoked

        assertThat(refresh(laptopNext).status()).isEqualTo(401);
        assertThat(refresh(phone.refreshToken()).status()).as("other families are untouched").isEqualTo(200);
    }

    @Test
    void twoConcurrentRefreshesOfOneTokenCannotBothSucceed() throws Exception {
        TestUser admin = users.communityAdmin();
        Session session = loginOk(admin);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return refresh(session.refreshToken()).status();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void logoutRevokesTheFamilyAndClearsTheCookie() {
        TestUser admin = users.communityAdmin();
        Session session = loginOk(admin);

        ApiClient.Response logout = api.post("/api/v1/auth/logout", null, ApiClient.cookieCall(session.refreshToken()));

        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.rawSetCookie("amanah_refresh")).contains("Max-Age=0");
        assertThat(refresh(session.refreshToken()).status()).isEqualTo(401);
        assertThat(auditCount(admin.id(), "LOGOUT")).isEqualTo(1);
    }

    @Test
    void logoutWithAnUnknownTokenIsStillQuietSuccess() {
        ApiClient.Response logout = api.post("/api/v1/auth/logout", null, ApiClient.cookieCall("not-a-real-token"));

        assertThat(logout.status()).isEqualTo(204);
    }

    @Test
    void logoutAllRevokesEveryDevice() {
        TestUser admin = users.communityAdmin();
        Session laptop = loginOk(admin);
        Session phone = loginOk(admin);

        ApiClient.Response response = api.post("/api/v1/auth/logout-all", null, "Authorization", laptop.bearer());

        assertThat(response.status()).isEqualTo(204);
        assertThat(refresh(laptop.refreshToken()).status()).isEqualTo(401);
        assertThat(refresh(phone.refreshToken()).status()).isEqualTo(401);
        assertThat(auditCount(admin.id(), "LOGOUT_ALL")).isEqualTo(1);
    }

    @Test
    void logoutAllNeedsAnAccessToken() {
        assertThat(api.post("/api/v1/auth/logout-all", null).status()).isEqualTo(401);
    }

    @Test
    void refreshRequiresTheCustomHeader() {
        Session session = loginOk(users.communityAdmin());

        ApiClient.Response response =
                api.post("/api/v1/auth/refresh", null, "Origin", ApiClient.ORIGIN, "Cookie", "amanah_refresh=" + session.refreshToken());

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("CSRF_HEADER_REQUIRED");
    }

    @Test
    void refreshRejectsAForeignOrMissingOrigin() {
        Session session = loginOk(users.communityAdmin());

        ApiClient.Response foreign = api.post("/api/v1/auth/refresh", null,
                "X-Requested-With", "amanah-web", "Origin", "https://evil.example", "Cookie", "amanah_refresh=" + session.refreshToken());
        ApiClient.Response missing = api.post("/api/v1/auth/refresh", null,
                "X-Requested-With", "amanah-web", "Cookie", "amanah_refresh=" + session.refreshToken());
        ApiClient.Response wrongValue = api.post("/api/v1/auth/refresh", null,
                "X-Requested-With", "XMLHttpRequest", "Origin", ApiClient.ORIGIN, "Cookie", "amanah_refresh=" + session.refreshToken());

        assertThat(foreign.status()).isEqualTo(403);
        assertThat(foreign.code()).isEqualTo("ORIGIN_NOT_ALLOWED");
        assertThat(missing.code()).isEqualTo("ORIGIN_NOT_ALLOWED");
        assertThat(wrongValue.code()).isEqualTo("CSRF_HEADER_REQUIRED");
        assertThat(refresh(session.refreshToken()).status()).as("rejected calls did not consume the token").isEqualTo(200);
    }

    @Test
    void logoutIsGuardedTheSameWay() {
        Session session = loginOk(users.communityAdmin());

        ApiClient.Response response = api.post("/api/v1/auth/logout", null, "Cookie", "amanah_refresh=" + session.refreshToken());

        assertThat(response.status()).isEqualTo(403);
        assertThat(refresh(session.refreshToken()).status()).as("a forged logout did nothing").isEqualTo(200);
    }

    @Test
    void thePercentEncodedPathCannotBypassTheCsrfGuard() {
        Session session = loginOk(users.communityAdmin());

        // %72 is "r": the server decodes it and routes to /refresh, so the guard must still apply
        ApiClient.Response refreshEncoded = api.post("/api/v1/auth/%72efresh", null, "Cookie", "amanah_refresh=" + session.refreshToken());
        ApiClient.Response logoutEncoded = api.post("/api/v1/auth/l%6fgout", null, "Cookie", "amanah_refresh=" + session.refreshToken());

        assertThat(refreshEncoded.status()).isEqualTo(403);
        assertThat(refreshEncoded.code()).isEqualTo("CSRF_HEADER_REQUIRED");
        assertThat(logoutEncoded.status()).isEqualTo(403);
        assertThat(refresh(session.refreshToken()).status()).as("the token was never consumed").isEqualTo(200);
    }

    @Test
    void refreshWithoutACookieIsRejected() {
        ApiClient.Response response = api.post("/api/v1/auth/refresh", null, "X-Requested-With", "amanah-web", "Origin", ApiClient.ORIGIN);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void anExpiredRefreshTokenIsRejected() {
        Session session = loginOk(users.communityAdmin());
        jdbc.update("update refresh_tokens set expires_at = now() - interval '1 second' where token_hash = ?", hash(session.refreshToken()));

        assertThat(refresh(session.refreshToken()).status()).isEqualTo(401);
    }

    @Test
    void refreshExtendsTheSlidingWindow() {
        Session session = loginOk(users.communityAdmin());
        jdbc.update("update refresh_tokens set expires_at = now() + interval '1 hour' where token_hash = ?", hash(session.refreshToken()));

        String next = refresh(session.refreshToken()).cookie("amanah_refresh");

        Boolean sevenDays = jdbc.queryForObject(
                "select expires_at > now() + interval '6 days 23 hours' from refresh_tokens where token_hash = ?", Boolean.class, hash(next));
        assertThat(sevenDays).isTrue();
    }

    @Test
    void aDisabledUserCannotRefresh() {
        TestUser admin = users.communityAdmin();
        Session session = loginOk(admin);
        jdbc.update("update users set status = 'DISABLED' where id = ?", admin.id());

        assertThat(refresh(session.refreshToken()).status()).isEqualTo(401);
    }

    @Test
    void theNewAccessTokenFromRefreshWorks() {
        Session session = loginOk(users.communityAdmin());

        String access = refresh(session.refreshToken()).json().get("accessToken").asString();

        assertThat(api.get("/api/v1/auth/me", "Authorization", ApiClient.bearer(access)).status()).isEqualTo(200);
    }

}

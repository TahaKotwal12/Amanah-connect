package com.amanahconnect.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.amanahconnect.support.AbstractDeskIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.AuthTestUsers.TestUser;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Secrets and personal data must not reach the logs, even at DEBUG, through login, refresh, reset and member flows. */
class LogHygieneIT extends AbstractDeskIT {

    private ListAppender<ILoggingEvent> appender;
    private Logger root;
    private Level before;
    private Level appBefore;

    @BeforeEach
    void capture() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        before = root.getLevel();
        Logger app = (Logger) LoggerFactory.getLogger("com.amanahconnect");
        appBefore = app.getLevel();
        app.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void release() {
        root.detachAppender(appender);
        root.setLevel(before);
        ((Logger) LoggerFactory.getLogger("com.amanahconnect")).setLevel(appBefore);
    }

    private String everything() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : new ArrayList<>(appender.list)) {
            sb.append(e.getFormattedMessage()).append('\n').append(e.getMDCPropertyMap()).append('\n');
            if (e.getThrowableProxy() != null) sb.append(ThrowableProxyUtil.asString(e.getThrowableProxy())).append('\n');
        }
        return sb.toString();
    }

    private void assertNoneOf(String what, String... secrets) {
        String logs = everything();
        assertThat(logs).as("the capture saw something").isNotBlank();
        for (String secret : secrets) {
            assertThat(secret).isNotBlank();
            assertThat(logs).as(what + " must not be logged").doesNotContain(secret);
        }
    }

    @Test
    void passwordsAndTokensNeverReachTheLogsThroughLoginRefreshAndLogout() {
        TestUser admin = users.extraAdminOf(communityA);

        ApiClient.Response wrong = api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", "Wrong-Password-1234"));
        ApiClient.Response unknown = api.post("/api/v1/auth/login", Map.of("email", "nobody-" + UUID.randomUUID() + "@example.test", "password", "Nobody-Pass-12345"));
        Session s = loginOk(admin);
        ApiClient.Response refreshed = refresh(s.refreshToken());
        ApiClient.Response reuse = refresh(s.refreshToken()); // reuse detection path logs a warning
        api.call("GET", "/api/v1/community/members", null, "Authorization", "Bearer " + s.accessToken().substring(0, s.accessToken().length() - 3) + "abc");

        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(reuse.status()).isEqualTo(401);
        assertNoneOf("a password, token or address", admin.password(), "Wrong-Password-1234", "Nobody-Pass-12345", s.accessToken(), s.refreshToken(),
                refreshed.cookie("amanah_refresh"), refreshed.json().get("accessToken").asString(), admin.email());
        assertThat(everything()).doesNotContainPattern(Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));
    }

    @Test
    void resetAndInviteTokensAndNewPasswordsNeverReachTheLogs() {
        TestUser admin = users.extraAdminOf(communityA);
        api.post("/api/v1/auth/password/forgot", Map.of("email", admin.email()));
        String payload = jdbc.queryForList("select payload::text from email_outbox where template = 'password-reset' and to_email = ? order by created_at desc", String.class, admin.email()).get(0);
        Matcher m = Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(payload);
        assertThat(m.find()).isTrue();
        String token = m.group(1);

        ApiClient.Response bad = api.post("/api/v1/auth/password/reset", Map.of("token", "x".repeat(43), "newPassword", "Brand-New-Pass-4242"));
        ApiClient.Response ok = api.post("/api/v1/auth/password/reset", Map.of("token", token, "newPassword", "Brand-New-Pass-4242"));
        api.post("/api/v1/auth/password/reset", Map.of("token", token, "newPassword", "Brand-New-Pass-4242")); // reuse

        assertThat(bad.status()).isGreaterThanOrEqualTo(400);
        assertThat(ok.status()).isLessThan(300);
        assertNoneOf("a reset token, password or address", token, "Brand-New-Pass-4242", admin.email());
    }

    @Test
    void membersPersonalDetailsStayOutOfTheLogs() {
        String email = "private-" + UUID.randomUUID() + "@example.test";
        UUID m = member(sessionA, "Zubeida Quraishi", email, true);
        asA("PATCH", "/api/v1/community/members/" + m, Map.of("phone", "+919812345678"));
        asA("POST", "/api/v1/community/complaints", Map.of("memberId", m.toString(), "subject", "Sensitive subject text", "description", "Sensitive description text"));
        invoiceA(m, "500.00", TODAY.plusDays(3));
        // a request the server rejects, to exercise the error logging path with the body present
        asA("POST", "/api/v1/community/members", Map.of("fullName", "Zubeida Quraishi", "email", "not-an-email", "consentEmail", true));

        assertNoneOf("member details", email, "Zubeida", "Quraishi", "+919812345678", "Sensitive description text");
    }

    @Test
    void theAuthorizationHeaderIsNeverLogged() {
        ApiClient.Response r = api.call("GET", "/api/v1/community/members", null, "Authorization", "Bearer secret-looking-value-123", "X-Request-Id", "req-for-test-1");

        assertThat(r.status()).isEqualTo(401);
        assertThat(everything()).doesNotContain("secret-looking-value-123");
    }
}

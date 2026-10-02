package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs with the bootstrap variables set, in its own application context and database, so no other
 * test can have created a SUPER_ADMIN first.
 */
@TestPropertySource(
        properties = {
            "app.bootstrap.super-admin-email=First.Admin@Example.Test",
            "app.bootstrap.super-admin-password=Correct-Horse-9-Staple"
        })
class BootstrapIT extends AbstractAuthIT {

    @Autowired SuperAdminBootstrap bootstrap;

    @Test
    void createsExactlyOneSuperAdminAtStartup() {
        Long superAdmins = jdbc.queryForObject("select count(*) from users where role = 'SUPER_ADMIN'", Long.class);
        assertThat(superAdmins).isOne();

        Map<String, Object> row = jdbc.queryForMap("select * from users where role = 'SUPER_ADMIN'");
        assertThat(row.get("email").toString()).isEqualTo("First.Admin@Example.Test");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("must_setup_2fa")).isEqualTo(true);
        assertThat(row.get("totp_enabled")).isEqualTo(false);
        assertThat((String) row.get("password_hash")).startsWith("$2a$").doesNotContain("Correct-Horse");
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).as("no other user was seeded").isOne();
    }

    @Test
    void isIdempotent() {
        assertThat(bootstrap.runOnce()).as("a SUPER_ADMIN exists, so nothing is created").isFalse();
        assertThat(bootstrap.runOnce()).isFalse();

        assertThat(jdbc.queryForObject("select count(*) from users where role = 'SUPER_ADMIN'", Long.class)).isOne();
    }

    @Test
    void theBootstrapAccountMustEnrolInTwoFactorBeforeDoingAnythingElse() {
        ApiClient.Response login = api.post("/api/v1/auth/login", Map.of("email", "first.admin@example.test", "password", "Correct-Horse-9-Staple"));

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.json().get("mfaSetupRequired").asBoolean()).isTrue();
        String bearer = ApiClient.bearer(login.json().get("accessToken").asString());
        assertThat(api.get("/api/v1/admin/communities", "Authorization", bearer).code()).isEqualTo("MFA_SETUP_REQUIRED");
        assertThat(api.post("/api/v1/auth/2fa/setup", null, "Authorization", bearer).status()).isEqualTo(200);
    }

    @Test
    void theCreationIsAuditedWithoutThePassword() {
        java.util.List<String> rows = jdbc.queryForList(
                "select coalesce(after::text,'') from audit_logs where action = 'SUPER_ADMIN_BOOTSTRAPPED'", String.class);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).doesNotContain("Correct-Horse").contains("F***@Example.Test");
    }
}

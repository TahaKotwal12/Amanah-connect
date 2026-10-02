package com.amanahconnect.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.auth.User;
import com.amanahconnect.auth.UserRole;
import com.amanahconnect.member.Member;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import com.amanahconnect.tenant.CurrentTenant;
import com.amanahconnect.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AuditIT extends AbstractTenantIT {

    private static final String BASE = "/api/v1/community/test-support";

    @Autowired AuditService auditService;
    @Autowired PlatformTransactionManager txManager;

    private List<Map<String, Object>> rows(String action, UUID entityId) {
        return jdbc.queryForList("select * from audit_logs where action = ? and entity_id = ?", action, entityId);
    }

    private long countByAction(String action, UUID communityId) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ? and community_id = ?", Long.class, action, communityId);
    }

    // ---- @Audited captures who, where, from where ---------------------------------------------

    @Test
    void anAuditedMutationRecordsActorCommunityIpUserAgentAndRequestId() {
        Member member = data.member(communityA);

        ApiClient.Response response = call(sessionA, "PUT", BASE + "/members/" + member.getId(), Map.of("fullName", "Renamed By Admin"),
                "X-Request-Id", "audit-req-1", "User-Agent", "AuditTest/1.0");

        assertThat(response.status()).isEqualTo(200);
        List<Map<String, Object>> rows = rows("TEST_MEMBER_RENAMED", member.getId());
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("actor_user_id")).isEqualTo(adminA.id());
        assertThat(row.get("community_id")).isEqualTo(communityA.getId());
        assertThat(row.get("entity_type")).isEqualTo("Member");
        assertThat(row.get("ip")).isNotNull();
        assertThat(row.get("user_agent")).isEqualTo("AuditTest/1.0");
        assertThat(row.get("request_id")).isEqualTo("audit-req-1");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("after").toString()).contains("Renamed By Admin");
        assertThat(row.get("before")).as("the aspect records the outcome; use AuditService for before/after diffs").isNull();
    }

    @Test
    void createAndDeleteAreAuditedWithTheNewEntityId() {
        ApiClient.Response created = asA("POST", BASE + "/members", Map.of("fullName", "Fresh Member"));
        UUID id = UUID.fromString(created.json().get("id").asString());

        asA("DELETE", BASE + "/members/" + id, null);

        assertThat(rows("TEST_MEMBER_CREATED", id)).hasSize(1);
        assertThat(rows("TEST_MEMBER_DELETED", id)).hasSize(1);
        assertThat(rows("TEST_MEMBER_CREATED", id).get(0).get("actor_user_id")).isEqualTo(adminA.id());
    }

    @Test
    void anActionByAdminAIsNeverRecordedAgainstCommunityB() {
        Member member = data.member(communityA);
        asA("PUT", BASE + "/members/" + member.getId(), Map.of("fullName", "Only A"));

        assertThat(countByAction("TEST_MEMBER_RENAMED", communityA.getId())).isOne();
        assertThat(countByAction("TEST_MEMBER_RENAMED", communityB.getId())).isZero();
    }

    @Test
    void aRejectedRequestWritesNoAuditRow() {
        Member ofB = data.member(communityB);

        ApiClient.Response blocked = asA("PUT", BASE + "/members/" + ofB.getId(), Map.of("fullName", "Nope"));

        assertThat(blocked.status()).isEqualTo(404);
        assertThat(rows("TEST_MEMBER_RENAMED", ofB.getId())).isEmpty();
    }

    // ---- atomicity ----------------------------------------------------------------------------

    @Test
    void ifTheMethodFailsNeitherTheChangeNorTheAuditRowSurvives() {
        Member member = data.member(communityA);

        ApiClient.Response response = asA("PUT", BASE + "/fail-after-save/" + member.getId(), Map.of("fullName", "Should Not Persist"));

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body()).as("no stack trace or message leaks").doesNotContain("boom").doesNotContain("Exception");
        assertThat(jdbc.queryForObject("select full_name from members where id = ?", String.class, member.getId())).isEqualTo(member.getFullName());
        assertThat(rows("TEST_FAILS_AFTER_SAVE", member.getId())).isEmpty();
    }

    @Test
    void ifTheAuditWriteFailsTheChangeIsRolledBackSoNothingHappensUnaudited() {
        Member member = data.member(communityA);

        ApiClient.Response response = asA("PUT", BASE + "/failing-audit/" + member.getId(), Map.of("fullName", "Unaudited Change"));

        assertThat(response.status()).isEqualTo(500);
        assertThat(jdbc.queryForObject("select full_name from members where id = ?", String.class, member.getId()))
                .as("a change that cannot be audited must not be applied")
                .isEqualTo(member.getFullName());
    }

    // ---- redaction through the real service ---------------------------------------------------

    @Test
    void secretsInBeforeAndAfterAreRedactedBeforeTheyAreStored() {
        UUID entityId = UUID.randomUUID();
        Map<String, Object> before = Map.of("fullName", "Old", "passwordHash", "$2a$12$oldhasholdhasholdhasholdhash", "nested", Map.of("accessToken", "tok-before"));
        Map<String, Object> after = Map.of("fullName", "New", "totpSecretEnc", "ciphertext-after", "recoveryCodes", List.of("AAAAAA-BBBBBB"));

        new TransactionTemplate(txManager).executeWithoutResult(s -> auditService.record("TEST_REDACTION", "User", entityId, before, after));

        Map<String, Object> row = rows("TEST_REDACTION", entityId).get(0);
        String stored = row.get("before").toString() + row.get("after").toString();
        assertThat(stored).contains("Old", "New", AuditRedactor.REDACTED);
        assertThat(stored).doesNotContain("$2a$12$old").doesNotContain("tok-before").doesNotContain("ciphertext-after").doesNotContain("AAAAAA-BBBBBB");
    }

    @Test
    void anEntityPassedAsAfterIsStoredWithoutItsSecrets() {
        User user = new User();
        user.setEmail("entity-" + UUID.randomUUID() + "@example.test");
        user.setFullName("Entity Test");
        user.setRole(UserRole.COMMUNITY_ADMIN);
        user.setPasswordHash("$2a$12$entityhashentityhashentityhashentityhashentityha");
        user.setTotpSecretEnc("entity-secret-ciphertext");
        UUID entityId = UUID.randomUUID();

        new TransactionTemplate(txManager).executeWithoutResult(s -> auditService.record("TEST_ENTITY_AFTER", "User", entityId, null, user));

        String stored = rows("TEST_ENTITY_AFTER", entityId).get(0).get("after").toString();
        assertThat(stored).contains("Entity Test").doesNotContain("$2a$").doesNotContain("entity-secret-ciphertext");
    }

    // ---- outside a request --------------------------------------------------------------------

    @Test
    void auditingWorksOutsideARequestForJobsWithAnExplicitTenant() {
        UUID entityId = UUID.randomUUID();
        CurrentTenant tenant = new CurrentTenant(communityA.getId(), adminA.id(), "ACTIVE", true);

        TenantContext.runAs(tenant, () -> new TransactionTemplate(txManager).executeWithoutResult(
                s -> auditService.record("TEST_JOB_ACTION", "Member", entityId, null, Map.of("k", "v"))));

        Map<String, Object> row = rows("TEST_JOB_ACTION", entityId).get(0);
        assertThat(row.get("community_id")).as("from the tenant context").isEqualTo(communityA.getId());
        assertThat(row.get("actor_user_id")).as("no authenticated user on a plain thread").isNull();
        assertThat(row.get("ip")).isNull();
    }
}

package com.amanahconnect.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SuspendedCommunityIT extends AbstractTenantIT {

    private static final String BASE = "/api/v1/community/test-support";

    private void setStatus(java.util.UUID communityId, String status) {
        jdbc.update("update communities set status = ? where id = ?", status, communityId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "ARCHIVED"})
    void writesAreForbiddenButReadsStillWork(String status) {
        var member = data.member(communityA);
        setStatus(communityA.getId(), status);

        ApiClient.Response read = asA("GET", BASE + "/members/" + member.getId(), null);
        ApiClient.Response list = asA("GET", BASE + "/members", null);
        ApiClient.Response whoami = asA("GET", BASE + "/whoami", null);

        assertThat(read.status()).as("reads allowed so admins can export their data").isEqualTo(200);
        assertThat(list.status()).isEqualTo(200);
        assertThat(whoami.json().get("status").asString()).isEqualTo(status);

        for (ApiClient.Response write : new ApiClient.Response[] {
            asA("POST", BASE + "/members", Map.of("fullName", "Blocked")),
            asA("PUT", BASE + "/members/" + member.getId(), Map.of("fullName", "Blocked")),
            asA("DELETE", BASE + "/members/" + member.getId(), null),
            asA("POST", BASE + "/noop", null)
        }) {
            assertThat(write.status()).as(write.body()).isEqualTo(403);
            assertThat(write.code()).isEqualTo("COMMUNITY_SUSPENDED");
            assertThat(write.header("Content-Type")).startsWith("application/problem+json");
            assertThat(write.json().get("detail").asString()).contains(status.toLowerCase());
        }
        assertThat(jdbc.queryForObject("select full_name from members where id = ?", String.class, member.getId())).as("nothing was written").isEqualTo(member.getFullName());
        assertThat(jdbc.queryForObject("select count(*) from members where community_id = ?", Long.class, communityA.getId())).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "PENDING"})
    void activeAndPendingCommunitiesCanWrite(String status) {
        setStatus(communityA.getId(), status);

        assertThat(asA("POST", BASE + "/members", Map.of("fullName", "Allowed")).status()).isEqualTo(201);
        assertThat(asA("POST", BASE + "/noop", null).status()).isEqualTo(204);
    }

    @Test
    void theStatusIsReadOnEveryRequestSoSuspendingTakesEffectImmediately() {
        assertThat(asA("POST", BASE + "/noop", null).status()).isEqualTo(204);

        setStatus(communityA.getId(), "SUSPENDED");
        assertThat(asA("POST", BASE + "/noop", null).code()).isEqualTo("COMMUNITY_SUSPENDED");

        setStatus(communityA.getId(), "ACTIVE");
        assertThat(asA("POST", BASE + "/noop", null).status()).as("and lifting it works at once too").isEqualTo(204);
    }

    @Test
    void suspendingOneCommunityDoesNotAffectAnother() {
        setStatus(communityA.getId(), "SUSPENDED");

        assertThat(asA("POST", BASE + "/noop", null).code()).isEqualTo("COMMUNITY_SUSPENDED");
        assertThat(asB("POST", BASE + "/noop", null).status()).isEqualTo(204);
    }

    @Test
    void tenantGuardRefusesWritesInServicesToo() {
        CurrentTenant suspended = new CurrentTenant(communityA.getId(), adminA.id(), "SUSPENDED", false);
        CurrentTenant active = new CurrentTenant(communityA.getId(), adminA.id(), "ACTIVE", true);
        TenantGuard guard = new TenantGuard();

        TenantContext.runAs(active, guard::requireWritable);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TenantContext.runAs(suspended, guard::requireWritable))
                .isInstanceOfSatisfying(com.amanahconnect.common.error.ApiException.class, e -> assertThat(e.code().name()).isEqualTo("COMMUNITY_SUSPENDED"));
    }
}

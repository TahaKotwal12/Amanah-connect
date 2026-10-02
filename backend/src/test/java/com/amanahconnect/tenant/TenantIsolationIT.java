package com.amanahconnect.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.plan.PlanRepository;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import com.amanahconnect.support.tenant.TestSupportService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cross-tenant isolation through real HTTP and security, using the harness on the test-support
 * endpoints, plus the Hibernate filter proved directly.
 */
class TenantIsolationIT extends AbstractTenantIT {

    private static final String BASE = "/api/v1/community/test-support";

    @Autowired MemberRepository members;
    @Autowired CommunityRepository communities;
    @Autowired PlanRepository plans;
    @Autowired TestSupportService service;
    @Autowired PlatformTransactionManager txManager;

    private Member memberOfB() {
        return data.member(communityB);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private CurrentTenant tenantA() {
        return new CurrentTenant(communityA.getId(), adminA.id(), "ACTIVE", true);
    }

    // ---- the harness rule, on a real endpoint set ---------------------------------------------

    @Test
    void adminACannotReadAResourceOfCommunityB() {
        Member member = memberOfB();

        assertCrossTenantRead(BASE + "/members/" + member.getId());
    }

    @Test
    void adminACannotUpdateAResourceOfCommunityB() {
        Member member = memberOfB();
        String before = jdbc.queryForObject("select full_name from members where id = ?", String.class, member.getId());

        assertCrossTenantUpdate("PUT", BASE + "/members/" + member.getId(), Map.of("fullName", "Hijacked"),
                () -> assertThat(jdbc.queryForObject("select full_name from members where id = ?", String.class, member.getId())).isEqualTo(before));

        assertThat(jdbc.queryForObject("select full_name from members where id = ?", String.class, member.getId())).as("only the owner's call changed it").isEqualTo("Hijacked");
    }

    @Test
    void adminACannotDeleteAResourceOfCommunityB() {
        Member member = memberOfB();

        assertCrossTenantDelete(BASE + "/members/" + member.getId(),
                () -> assertThat(jdbc.queryForObject("select deleted_at from members where id = ?", java.sql.Timestamp.class, member.getId())).isNull());
    }

    @Test
    void listsOnlyEverShowTheCallersOwnCommunity() {
        Member own = data.member(communityA);
        Member other = memberOfB();

        assertListHides(BASE + "/members", other.getId());

        ApiClient.Response forA = asA("GET", BASE + "/members", null);
        assertThat(forA.json().get("total").asInt()).isEqualTo(1);
        assertThat(forA.body()).contains(own.getId().toString());
    }

    @Test
    void creatingCannotTargetAnotherCommunityThroughQueryHeaderOrBody() {
        assertCreateCannotTargetOtherTenant(
                BASE + "/members",
                Map.of("fullName", "New Person"),
                response -> UUID.fromString(response.json().get("id").asString()),
                BASE + "/members/%s");

        assertThat(jdbc.queryForObject("select count(*) from members where community_id = ?", Long.class, communityB.getId())).as("nothing was created in B").isZero();
        assertThat(jdbc.queryForObject("select count(*) from members where community_id = ?", Long.class, communityA.getId())).isOne();
    }

    @Test
    void theHarnessRecordsWhichEndpointsItExercised() {
        Member member = memberOfB();

        assertCrossTenantRead(BASE + "/members/" + member.getId());

        assertThat(com.amanahconnect.support.tenant.CrossTenantCoverage.covered())
                .contains(com.amanahconnect.support.tenant.TestSupportController.class.getName() + "#get");
    }

    // ---- where the community comes from ---------------------------------------------------------

    @Test
    void theCommunityComesFromThePrincipalNotFromTheRequest() {
        ApiClient.Response response = call(sessionA, "GET", BASE + "/whoami?communityId=" + communityB.getId(), null, "X-Community-Id", communityB.getId().toString());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("communityIdParam").asString()).isEqualTo(communityA.getId().toString());
        assertThat(response.json().get("communityIdFromTenant").asString()).isEqualTo(communityA.getId().toString());
        assertThat(response.json().get("userId").asString()).isEqualTo(adminA.id().toString());
        assertThat(response.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(asB("GET", BASE + "/whoami", null).json().get("communityIdParam").asString()).isEqualTo(communityB.getId().toString());
    }

    @Test
    void anAdminWithoutACommunityGetsForbiddenNotAnotherTenant() {
        var orphan = users.create(com.amanahconnect.auth.UserRole.COMMUNITY_ADMIN, com.amanahconnect.auth.UserStatus.ACTIVE);
        Session session = loginOk(orphan);

        ApiClient.Response response = call(session, "GET", BASE + "/whoami", null);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void consecutiveRequestsNeverInheritAPreviousTenant() {
        for (int i = 0; i < 6; i++) {
            Session session = i % 2 == 0 ? sessionA : sessionB;
            UUID expected = i % 2 == 0 ? communityA.getId() : communityB.getId();

            ApiClient.Response response = call(session, "GET", BASE + "/whoami", null);

            assertThat(response.json().get("communityIdParam").asString()).as("request %d", i).isEqualTo(expected.toString());
        }
    }

    // ---- the Hibernate filter (second safety net) ----------------------------------------------

    @Test
    void theFilterStopsAnUnscopedQueryFromLeakingAnotherCommunitysRow() {
        Member member = memberOfB();

        // The service method has NO community predicate. Through HTTP the filter hides B's row from A...
        ApiClient.Response forA = asA("GET", BASE + "/unscoped-members/" + member.getId(), null);
        ApiClient.Response forB = asB("GET", BASE + "/unscoped-members/" + member.getId(), null);
        assertThat(forA.status()).as("filter hides it from A: %s", forA.body()).isEqualTo(404);
        assertThat(forB.status()).as("the owner still sees it (control)").isEqualTo(200);

        // ...and with no tenant bound the very same query finds it, so the filter is what stopped the leak.
        assertThat(service.unscopedGet(member.getId()).id()).isEqualTo(member.getId());
    }

    @Test
    void theFilterAlsoNarrowsCountsAndDerivedQueries() {
        memberOfB();

        long withoutTenant = members.countByCommunityId(communityB.getId());
        long asA = TenantContext.callAs(tenantA(), () -> tx().execute(s -> members.countByCommunityId(communityB.getId())));

        assertThat(withoutTenant).isEqualTo(1);
        assertThat(asA).as("asking A's session for B's rows finds none, even with B's id spelled out").isZero();
    }

    @Test
    void theCommunityTableIsRestrictedToTheCallersOwnRow() {
        List<UUID> visible = TenantContext.callAs(tenantA(), () -> tx().execute(s -> communities.findAll().stream().map(c -> c.getId()).toList()));

        assertThat(visible).containsExactly(communityA.getId());
        assertThat(communities.findAll().size()).as("without a tenant everything is visible (super admin, system jobs)").isGreaterThan(1);
    }

    @Test
    void tablesThatAreNotTenantDataAreNeverFiltered() {
        long plansInTenant = TenantContext.callAs(tenantA(), () -> tx().execute(s -> (long) plans.findAll().size()));

        assertThat(plansInTenant).isEqualTo(plans.count());
    }

    @Test
    void callAsSwitchesTheFilterOnInsideARunningTransactionAndRestoresIt() {
        memberOfB();

        List<Long> counts = tx().execute(status -> {
            long before = members.countByCommunityId(communityB.getId());
            long inside = TenantContext.callAs(tenantA(), () -> members.countByCommunityId(communityB.getId()));
            long after = members.countByCommunityId(communityB.getId());
            return List.of(before, inside, after);
        });

        assertThat(counts).containsExactly(1L, 0L, 1L);
    }

    @Test
    void nestedCallAsRestoresThePreviousTenant() {
        CurrentTenant a = tenantA();
        CurrentTenant b = new CurrentTenant(communityB.getId(), adminB.id(), "ACTIVE", true);

        TenantContext.runAs(a, () -> {
            assertThat(TenantContext.require().communityId()).isEqualTo(communityA.getId());
            TenantContext.runAs(b, () -> assertThat(TenantContext.require().communityId()).isEqualTo(communityB.getId()));
            assertThat(TenantContext.require().communityId()).isEqualTo(communityA.getId());
        });

        assertThat(TenantContext.current()).as("cleared afterwards").isEmpty();
    }

    @Test
    void requiringATenantWhenNoneIsBoundIsForbiddenNotNullPointer() {
        org.assertj.core.api.Assertions.assertThatThrownBy(TenantContext::require)
                .isInstanceOfSatisfying(com.amanahconnect.common.error.ApiException.class, e -> assertThat(e.code().name()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void anExceptionInsideCallAsStillUnbindsTheTenant() {
        try {
            TenantContext.runAs(tenantA(), () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // expected
        }

        assertThat(TenantContext.current()).isEmpty();
    }
}

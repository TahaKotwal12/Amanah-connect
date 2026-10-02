package com.amanahconnect.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.Community;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.AuthTestUsers.TestUser;
import com.amanahconnect.support.TestData;
import com.amanahconnect.common.error.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Plan limits against a real database and over real HTTP. */
class PlanLimitIT extends AbstractAuthIT {

    private static final String MEMBERS = "/api/v1/community/test-support/members";

    @Autowired TestData data;
    @Autowired PlanLimitService planLimits;
    @Autowired PlatformTransactionManager txManager;

    private Map<String, Object> limits(Object members, Object emails) {
        Map<String, Object> map = new HashMap<>();
        map.put("max_members", members);
        map.put("emails_per_month", emails);
        return map;
    }

    private Session adminOf(Community community) {
        TestUser admin = users.communityAdminOf(community);
        return loginOk(admin);
    }

    private ApiClient.Response create(Session session, String name) {
        return api.post(MEMBERS, Map.of("fullName", name), "Authorization", session.bearer());
    }

    @Test
    void theMemberAfterTheLimitGetsA402ThatNamesTheLimitAndThePlan() {
        Plan tiny = data.customPlan("Tiny Test", limits(2, null), Map.of());
        Community community = data.communityOn(tiny);
        Session session = adminOf(community);

        assertThat(create(session, "One").status()).isEqualTo(201);
        assertThat(create(session, "Two").status()).isEqualTo(201);
        ApiClient.Response refused = create(session, "Three");

        assertThat(refused.status()).isEqualTo(402);
        assertThat(refused.code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED.name());
        assertThat(refused.header("Content-Type")).startsWith("application/problem+json");
        assertThat(refused.json().get("detail").asString()).contains("Tiny Test plan", "2 members");
        assertThat(refused.json().get("limit").asString()).isEqualTo("max_members");
        assertThat(refused.json().get("limitValue").asInt()).isEqualTo(2);
        assertThat(refused.json().get("current").asInt()).isEqualTo(2);
        assertThat(refused.json().get("plan").asString()).isEqualTo(tiny.getCode());
        assertThat(jdbc.queryForObject("select count(*) from members where community_id = ?", Long.class, community.getId())).as("the refused member was not created").isEqualTo(2);
    }

    @Test
    void removingAMemberFreesARoomBecauseSoftDeletedMembersDoNotCount() {
        Plan tiny = data.customPlan("Tiny Test", limits(1, null), Map.of());
        Session session = adminOf(data.communityOn(tiny));
        UUID id = UUID.fromString(create(session, "Only").json().get("id").asString());
        assertThat(create(session, "Blocked").status()).isEqualTo(402);

        assertThat(api.delete(MEMBERS + "/" + id, "Authorization", session.bearer()).status()).isEqualTo(200);

        assertThat(create(session, "Allowed now").status()).isEqualTo(201);
    }

    @Test
    void anUnlimitedPlanNeverBlocks() {
        Plan unlimited = data.customPlan("Unlimited Test", limits(null, null), Map.of());
        Session session = adminOf(data.communityOn(unlimited));

        for (int i = 0; i < 5; i++) {
            assertThat(create(session, "M" + i).status()).isEqualTo(201);
        }
    }

    @Test
    void aSuperAdminChangingThePlanTakesEffectOnTheNextRequest() {
        Plan plan = data.customPlan("Adjustable", limits(1, null), Map.of());
        Session session = adminOf(data.communityOn(plan));
        create(session, "One");
        assertThat(create(session, "Two").status()).isEqualTo(402);

        jdbc.update("update plans set limits = '{\"max_members\": 3}'::jsonb where id = ?", plan.getId());

        assertThat(create(session, "Two").status()).isEqualTo(201);
    }

    @Test
    void eachCommunityIsMeasuredOnItsOwnPlanAndItsOwnMembers() {
        Plan tiny = data.customPlan("Tiny Test", limits(1, null), Map.of());
        Session small = adminOf(data.communityOn(tiny));
        Session roomy = adminOf(data.communityOn(data.customPlan("Roomy Test", limits(10, null), Map.of())));

        create(small, "One");

        assertThat(create(small, "Two").status()).isEqualTo(402);
        assertThat(create(roomy, "One").status()).isEqualTo(201);
    }

    @Test
    void emailQuotaCountsThisMonthsQueuedEmailsOnly() {
        Plan plan = data.customPlan("Mailer", limits(null, 3), Map.of());
        Community community = data.communityOn(plan);
        insertEmail(community, "PENDING", "now()");
        insertEmail(community, "SENT", "now()");
        insertEmail(community, "FAILED", "now()");                       // failures do not use quota
        insertEmail(community, "SENT", "now() - interval '40 days'");   // last month does not count

        new TransactionTemplate(txManager).executeWithoutResult(s -> planLimits.checkEmailQuota(community.getId())); // 2 of 3 used
        insertEmail(community, "PENDING", "now()");                      // now 3 of 3

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TransactionTemplate(txManager).executeWithoutResult(s -> planLimits.checkEmailQuota(community.getId())))
                .isInstanceOfSatisfying(PlanLimitExceededException.class, e -> assertThat(e.getMessage()).contains("Mailer plan", "3 emails per month"));
    }

    @Test
    void featuresComeFromThePlan() {
        Plan with = data.customPlan("With PDF", limits(null, null), Map.of("pdf_reports", true));
        Plan without = data.customPlan("No PDF", limits(null, null), Map.of("pdf_reports", false));
        Community allowed = data.communityOn(with);
        Community blocked = data.communityOn(without);

        new TransactionTemplate(txManager).executeWithoutResult(s -> planLimits.requireFeature(allowed.getId(), PlanLimitKeys.FEATURE_PDF_REPORTS));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TransactionTemplate(txManager).executeWithoutResult(s -> planLimits.requireFeature(blocked.getId(), PlanLimitKeys.FEATURE_PDF_REPORTS)))
                .isInstanceOf(PlanFeatureUnavailableException.class);
    }

    @Test
    void theSeededStarterPlanHasTheDocumentedLimits() {
        Community starter = data.community();

        PlanSnapshot plan = planLimits.planOf(starter.getId()); // usable after the transaction has ended

        assertThat(plan.code()).isEqualTo("STARTER");
        assertThat(((Number) plan.limits().get("max_members")).intValue()).isEqualTo(100);
        assertThat(plan.features()).containsEntry("pdf_reports", false);
    }

    private void insertEmail(Community community, String status, String createdAt) {
        jdbc.update(
                "insert into email_outbox (community_id, to_email, template, status, created_at) values (?, ?, 'test', ?, " + createdAt + ")",
                community.getId(), "x-" + UUID.randomUUID() + "@example.test", status);
    }
}

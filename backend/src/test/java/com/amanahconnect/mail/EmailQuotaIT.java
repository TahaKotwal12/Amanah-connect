package com.amanahconnect.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.plan.Plan;
import com.amanahconnect.plan.PlanLimitService;
import com.amanahconnect.support.ApiClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class EmailQuotaIT extends AbstractMailIT {

    private static final String USAGE = "/api/v1/community/email-usage";

    @Autowired PlanLimitService planLimits;

    private long queuedToday() {
        return count("select count(*) from email_outbox where community_id = ? and status <> 'FAILED' and created_at >= date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata'", communityA.getId());
    }

    private void plan(Map<String, Object> limits) {
        Plan p = data.customPlan("Email limits " + UUID.randomUUID().toString().substring(0, 6), limits, Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", p.getId(), communityA.getId());
    }

    private void queueFor(int n) {
        for (int i = 0; i < n; i++) queueSample(communityA.getId(), email(), "member-welcome");
    }

    // ---- the day and the month ---------------------------------------------------------------------------------------------

    @Test
    void theDailyLimitStopsEmailsEvenWhenTheMonthHasRoom() {
        long today = queuedToday();
        plan(Map.of("emails_per_month", 1000, "emails_per_day", today + 2));

        assertThat(planLimits.emailQuotaRemaining(communityA.getId())).isEqualTo(2);
        queueFor(2);

        assertThat(planLimits.emailQuotaRemaining(communityA.getId())).isZero();
        assertThat(planLimits.hasEmailQuota(communityA.getId(), 1)).isFalse();
    }

    @Test
    void theMonthlyLimitStopsEmailsEvenWhenTheDayHasRoom() {
        long month = count("select count(*) from email_outbox where community_id = ? and status <> 'FAILED'", communityA.getId());
        plan(Map.of("emails_per_month", month + 1, "emails_per_day", 500));

        assertThat(planLimits.emailQuotaRemaining(communityA.getId())).isEqualTo(1);
    }

    @Test
    void yesterdaysMailCountsForTheMonthButNotForToday() {
        queueFor(3);
        jdbc.update("update email_outbox set created_at = date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata' - interval '1 hour' where community_id = ? and template = 'member-welcome'", communityA.getId());
        long today = queuedToday();
        long month = count("select count(*) from email_outbox where community_id = ? and status <> 'FAILED' and created_at >= date_trunc('month', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata'", communityA.getId());
        plan(Map.of("emails_per_month", month + 10, "emails_per_day", today + 5));

        PlanLimitService.EmailUsage usage = planLimits.emailUsage(communityA.getId());

        assertThat(usage.usedToday()).isEqualTo(today);
        assertThat(usage.dailyLimit()).isEqualTo(today + 5);
        assertThat(usage.usedThisMonth()).isEqualTo(month);
        assertThat(planLimits.emailQuotaRemaining(communityA.getId())).isEqualTo(5);
    }

    @Test
    void failedMailDoesNotUseUpTheAllowance() {
        long today = queuedToday();
        plan(Map.of("emails_per_day", today + 3));
        UUID a = queueSample(communityA.getId(), email(), "member-welcome");
        queueFor(2);
        assertThat(planLimits.emailQuotaRemaining(communityA.getId())).isZero();

        jdbc.update("update email_outbox set status = 'FAILED' where id = ?", a);

        assertThat(planLimits.emailQuotaRemaining(communityA.getId())).isEqualTo(1);
    }

    @Test
    void aPlanWithNoLimitsIsUnlimited() {
        plan(Map.of());

        assertThat(planLimits.emailQuotaRemaining(communityA.getId())).isEqualTo(Long.MAX_VALUE);
        assertThat(planLimits.hasEmailQuota(communityA.getId(), 1_000_000)).isTrue();
        PlanLimitService.EmailUsage usage = planLimits.emailUsage(communityA.getId());
        assertThat(usage.dailyLimit()).isNull();
        assertThat(usage.monthlyLimit()).isNull();
    }

    // ---- who the limit applies to -----------------------------------------------------------------------------------------

    @Test
    void anAnnouncementIsCutOffAtTheTighterOfTheTwoLimits() {
        for (int i = 0; i < 5; i++) member(sessionA, "Reader " + i, email(), true);
        long today = queuedToday();
        plan(Map.of("emails_per_month", 1000, "emails_per_day", today + 2));
        ApiClient.Response created = asA("POST", "/api/v1/community/announcements", Map.of("title", "Notice", "body", "<p>Hi</p>", "sendEmail", true));
        UUID id = id(created.json());

        JsonNode sent = asA("POST", "/api/v1/community/announcements/" + id + "/send", null).json();

        assertThat(sent.get("delivery").get("emailsQueued").asInt()).isEqualTo(2);
        assertThat(sent.get("delivery").get("skippedQuota").asInt()).isEqualTo(3);
    }

    @Test
    void platformMailIsNotChargedToAnyCommunity() {
        long before = queuedToday();
        plan(Map.of("emails_per_day", before + 1));

        queue(null, email(), "password-reset", MailSamples.payload("password-reset"));
        queue(null, email(), "support-message", MailSamples.payload("support-message"));

        assertThat(planLimits.emailQuotaRemaining(communityA.getId())).isEqualTo(1);
    }

    @Test
    void theApiRefusesAnEmailWhenTheDayIsFull() {
        long today = queuedToday();
        plan(Map.of("emails_per_day", today));
        UUID id = id(asA("POST", "/api/v1/community/announcements", Map.of("title", "Notice", "body", "<p>Hi</p>")).json());

        ApiClient.Response test = asA("POST", "/api/v1/community/announcements/" + id + "/send-test", null);

        assertThat(test.status()).isEqualTo(402);
        assertThat(test.json().get("limit").asString()).isEqualTo("emails_per_day");
    }

    // ---- the usage view ---------------------------------------------------------------------------------------------------

    @Test
    void theCommunityCanSeeItsUsageAgainstItsLimits() {
        long today = queuedToday();
        long month = count("select count(*) from email_outbox where community_id = ? and status <> 'FAILED'", communityA.getId());
        plan(Map.of("emails_per_month", 400, "emails_per_day", 50));
        queueFor(3);

        JsonNode usage = asA("GET", USAGE, null).json();

        assertThat(usage.get("usedToday").asLong()).isEqualTo(today + 3);
        assertThat(usage.get("dailyLimit").asLong()).isEqualTo(50);
        assertThat(usage.get("usedThisMonth").asLong()).isEqualTo(month + 3);
        assertThat(usage.get("monthlyLimit").asLong()).isEqualTo(400);
    }

    @Test
    void usageIsPerCommunityAndIsolated() {
        queueFor(4);
        for (int i = 0; i < 2; i++) queueSample(communityB.getId(), email(), "member-welcome");

        JsonNode a = asA("GET", USAGE, null).json();
        JsonNode b = asB("GET", USAGE, null).json();

        assertThat(a.get("usedToday").asLong()).isEqualTo(queuedToday());
        assertThat(b.get("usedToday").asLong()).isEqualTo(2);
        assertTenantSingleton("GET", USAGE, null, () -> asB("GET", USAGE, null).body());
    }

    @Test
    void anAdminPlanCanCarryADailyLimit() {
        ApiClient.Response created = asSuperPlan("EMAILDAY" + UUID.randomUUID().toString().substring(0, 4).toUpperCase());

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.json().get("limits").get("emails_per_day").asInt()).isEqualTo(25);
    }

    private ApiClient.Response asSuperPlan(String code) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Plan " + code);
        body.put("priceMonthly", "100.00");
        body.put("priceYearly", "1000.00");
        body.put("limits", Map.of("max_members", 50, "emails_per_month", 500, "emails_per_day", 25));
        body.put("features", Map.of("exports", true));
        return asSuper("POST", "/api/v1/admin/plans", body);
    }
}

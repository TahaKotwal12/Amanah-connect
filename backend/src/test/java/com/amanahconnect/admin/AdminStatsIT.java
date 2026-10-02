package com.amanahconnect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.Community;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.AbstractAdminIT;
import com.amanahconnect.support.TestData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdminStatsIT extends AbstractAdminIT {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private JsonNode stats() {
        var response = adminGet(ADMIN + "/stats");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private void setStatus(Community community, String status) {
        jdbc.update("update communities set status = ? where id = ?", status, community.getId());
    }

    private void paid(Community community, Plan plan, LocalDate paidOn, String amount, String status, String reference) {
        UUID id = insertSubscription(community, plan, paidOn, paidOn.plusDays(365), status, reference, amount);
        jdbc.update("update platform_subscriptions set paid_on = ? where id = ?", paidOn, id);
    }

    @Test
    void countsCommunitiesMembersRevenueExpiryLeadsAndThreadsExactly() {
        JsonNode before = stats();
        Plan plan = data.plan("STARTER");

        Community active = data.communityOn(plan);
        Community suspended = data.communityOn(plan);
        setStatus(suspended, "SUSPENDED");
        Community pending = data.communityOn(plan);
        setStatus(pending, "PENDING");
        Community archived = data.communityOn(plan);
        setStatus(archived, "ARCHIVED");

        data.member(active);
        data.member(active);
        data.member(suspended);
        data.member(archived);
        UUID deleted = data.member(active).getId();
        jdbc.update("update members set deleted_at = now() where id = ?", deleted);

        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate yearStart = TODAY.withDayOfYear(1);
        paid(active, plan, TODAY, "1000.50", "ACTIVE", "S1-" + TestData.unique());
        paid(suspended, plan, monthStart, "200.25", "ACTIVE", "S2-" + TestData.unique());
        paid(pending, plan, TODAY, "999.00", "CANCELLED", "S3-" + TestData.unique());
        // Earlier this year (but not this month) counts towards the year only. In January there is no such day.
        boolean hasEarlierMonth = TODAY.getMonthValue() > 1;
        if (hasEarlierMonth) {
            paid(archived, plan, yearStart, "300.00", "ACTIVE", "S4-" + TestData.unique());
        }
        paid(active, plan, yearStart.minusDays(1), "5000.00", "EXPIRED", "S5-" + TestData.unique());
        BigDecimal addedMonth = new BigDecimal("1200.75");
        BigDecimal addedYear = hasEarlierMonth ? addedMonth.add(new BigDecimal("300.00")) : addedMonth;

        // Expiring: one ending in 10 days, one renewed (not counted), one ending in 90 days (outside the window).
        Community expiring = data.communityOn(plan);
        insertSubscription(expiring, plan, TODAY.minusDays(300), TODAY.plusDays(10), "ACTIVE", "E1-" + TestData.unique(), "10.00");
        Community renewed = data.communityOn(plan);
        insertSubscription(renewed, plan, TODAY.minusDays(300), TODAY.plusDays(10), "ACTIVE", "E2-" + TestData.unique(), "10.00");
        insertSubscription(renewed, plan, TODAY.plusDays(10), TODAY.plusDays(375), "ACTIVE", "E3-" + TestData.unique(), "10.00");
        Community far = data.communityOn(plan);
        insertSubscription(far, plan, TODAY.minusDays(100), TODAY.plusDays(90), "ACTIVE", "E4-" + TestData.unique(), "10.00");

        jdbc.update("update platform_subscriptions set paid_on = ? where reference like 'E_-%'", yearStart.minusDays(2));

        // Leads and support threads.
        jdbc.update("insert into leads (id, name, email, status) values (gen_random_uuid(), 'A', ?, 'NEW')", "stat-a-" + TestData.unique() + "@example.test");
        jdbc.update("insert into leads (id, name, email, status) values (gen_random_uuid(), 'B', ?, 'CONTACTED')", "stat-b-" + TestData.unique() + "@example.test");
        for (String status : new String[] {"OPEN", "WAITING", "RESOLVED", "CLOSED"}) {
            jdbc.update("insert into support_threads (id, community_id, subject, status, created_by) values (gen_random_uuid(), ?, ?, ?, ?)",
                    active.getId(), "Subject " + status, status, superAdmin.id());
        }

        JsonNode after = stats();

        // Communities created: active, suspended, pending, archived, expiring, renewed, far = 3 more active (+expiring, renewed, far), etc.
        assertThat(delta(before, after, "communities", "active")).isEqualTo(4);
        assertThat(delta(before, after, "communities", "suspended")).isEqualTo(1);
        assertThat(delta(before, after, "communities", "pending")).isEqualTo(1);
        assertThat(delta(before, after, "communities", "archived")).isEqualTo(1);
        assertThat(delta(before, after, "communities", "total")).as("archived is not in the total").isEqualTo(6);
        assertThat(after.get("totalMembers").asLong() - before.get("totalMembers").asLong()).as("not deleted, not in archived communities").isEqualTo(3);
        assertThat(money(after, "thisMonth").subtract(money(before, "thisMonth"))).isEqualByComparingTo(addedMonth);
        assertThat(money(after, "thisYear").subtract(money(before, "thisYear"))).isEqualByComparingTo(addedYear);
        assertThat(after.get("platformRevenue").get("thisMonth").isString()).as("money is a string").isTrue();
        assertThat(after.get("platformRevenue").get("currency").asString()).isEqualTo("INR");
        assertThat(after.get("expiringSubscriptions").asLong() - before.get("expiringSubscriptions").asLong()).isEqualTo(1);
        assertThat(after.get("expiringWithinDays").asInt()).isEqualTo(30);
        assertThat(after.get("newLeads").asLong() - before.get("newLeads").asLong()).isEqualTo(1);
        assertThat(after.get("openSupportThreads").asLong() - before.get("openSupportThreads").asLong()).isEqualTo(2);
    }

    private long delta(JsonNode before, JsonNode after, String group, String field) {
        return after.get(group).get(field).asLong() - before.get(group).get(field).asLong();
    }

    private BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get("platformRevenue").get(field).asString());
    }
}

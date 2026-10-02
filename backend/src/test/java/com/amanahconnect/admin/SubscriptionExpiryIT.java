package com.amanahconnect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.plan.Plan;
import com.amanahconnect.plan.SubscriptionExpiryJob;
import com.amanahconnect.plan.SubscriptionExpiryService;
import com.amanahconnect.plan.SubscriptionExpiryService.Policy;
import com.amanahconnect.plan.SubscriptionProperties;
import com.amanahconnect.support.AbstractAdminIT;
import com.amanahconnect.support.TestData;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SubscriptionExpiryIT extends AbstractAdminIT {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final Policy WARN_ONLY = new Policy(false, 7);
    private static final Policy AUTO_SUSPEND = new Policy(true, 7);

    @Autowired SubscriptionExpiryService service;
    @Autowired SubscriptionExpiryJob job;
    @Autowired SubscriptionProperties properties;
    @Autowired LockProvider lockProvider;
    @Autowired Clock clock;

    /** An ACTIVE community with an owner (created through the API), so emails have somewhere to go. */
    private UUID activeCommunity() {
        UUID id = createCommunityViaApi("Expiry " + TestData.unique());
        jdbc.update("update communities set status = 'ACTIVE' where id = ?", id);
        return id;
    }

    private String ownerEmail(UUID communityId) {
        return jdbc.queryForObject("select u.email from users u join communities c on c.owner_user_id = u.id where c.id = ?", String.class, communityId);
    }

    private UUID subscribe(UUID communityId, LocalDate start, LocalDate end) {
        Plan plan = data.plan("STARTER");
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into platform_subscriptions (id, community_id, plan_id, period_start, period_end, amount, reference, status, recorded_by, paid_on)"
                        + " values (?, ?, ?, ?, ?, 1000, ?, 'ACTIVE', ?, ?)",
                id, communityId, plan.getId(), start, end, "R-" + TestData.unique(), superAdmin.id(), start);
        return id;
    }

    private String statusOfSubscription(UUID id) {
        return jdbc.queryForObject("select status from platform_subscriptions where id = ?", String.class, id);
    }

    private String statusOfCommunity(UUID id) {
        return jdbc.queryForObject("select status from communities where id = ?", String.class, id);
    }

    @Test
    void sendsTheSevenDayReminderOnceThenTheOneDayReminderOnce() {
        UUID community = activeCommunity();
        String owner = ownerEmail(community);
        subscribe(community, TODAY.minusDays(358), TODAY.plusDays(7));

        service.execute(TODAY, WARN_ONLY);
        assertThat(emailsTo(owner, "subscription-expiring")).hasSize(1);
        assertThat(emailsTo(owner, "subscription-expiring").get(0).get("payload").toString()).contains("\"daysLeft\"").contains("7");

        service.execute(TODAY, WARN_ONLY);
        assertThat(emailsTo(owner, "subscription-expiring")).as("idempotent: same day, same reminder").hasSize(1);

        service.execute(TODAY.plusDays(3), WARN_ONLY);
        assertThat(emailsTo(owner, "subscription-expiring")).as("still only the 7-day reminder four days out").hasSize(1);

        service.execute(TODAY.plusDays(6), WARN_ONLY);
        assertThat(emailsTo(owner, "subscription-expiring")).as("the 1-day reminder").hasSize(2);

        service.execute(TODAY.plusDays(6), WARN_ONLY);
        service.execute(TODAY.plusDays(7), WARN_ONLY);
        assertThat(emailsTo(owner, "subscription-expiring")).as("no repeats").hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'SUBSCRIPTION_REMINDER_SENT' and community_id = ?", Long.class, community)).isEqualTo(2);
    }

    @Test
    void aSubscriptionFirstSeenOneDayOutGetsASingleReminder() {
        UUID community = activeCommunity();
        String owner = ownerEmail(community);
        UUID sub = subscribe(community, TODAY.minusDays(364), TODAY.plusDays(1));

        service.execute(TODAY, WARN_ONLY);
        service.execute(TODAY, WARN_ONLY);

        assertThat(emailsTo(owner, "subscription-expiring")).hasSize(1);
        Map<String, Object> row = jdbc.queryForMap("select reminder_7d_sent_at, reminder_1d_sent_at from platform_subscriptions where id = ?", sub);
        assertThat(row.get("reminder_1d_sent_at")).isNotNull();
        assertThat(row.get("reminder_7d_sent_at")).as("the 1-day reminder covers the 7-day one").isNotNull();
    }

    @Test
    void doesNotRemindForSubscriptionsFurtherOutOrAlreadyRenewed() {
        UUID far = activeCommunity();
        subscribe(far, TODAY.minusDays(100), TODAY.plusDays(8));
        UUID renewed = activeCommunity();
        subscribe(renewed, TODAY.minusDays(360), TODAY.plusDays(5));
        subscribe(renewed, TODAY.plusDays(5), TODAY.plusDays(370));

        service.execute(TODAY, WARN_ONLY);

        assertThat(emailsTo(ownerEmail(far), "subscription-expiring")).isEmpty();
        assertThat(emailsTo(ownerEmail(renewed), "subscription-expiring")).as("a renewal suppresses the reminder").isEmpty();
    }

    @Test
    void marksLapsedSubscriptionsExpiredAndNotifiesTheOwnerOnce() {
        UUID community = activeCommunity();
        String owner = ownerEmail(community);
        UUID sub = subscribe(community, TODAY.minusDays(366), TODAY.minusDays(1));

        service.execute(TODAY, WARN_ONLY);

        assertThat(statusOfSubscription(sub)).isEqualTo("EXPIRED");
        assertThat(emailsTo(owner, "subscription-expired")).hasSize(1);
        assertThat(jdbc.queryForObject("select expired_notified_at from platform_subscriptions where id = ?", java.sql.Timestamp.class, sub)).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'SUBSCRIPTION_EXPIRED' and entity_id = ?", Long.class, sub)).isOne();

        service.execute(TODAY, WARN_ONLY);
        assertThat(emailsTo(owner, "subscription-expired")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'SUBSCRIPTION_EXPIRED' and entity_id = ?", Long.class, sub)).as("no second pass").isOne();
    }

    @Test
    void anExpiringSubscriptionThatWasRenewedExpiresQuietly() {
        UUID community = activeCommunity();
        String owner = ownerEmail(community);
        UUID old = subscribe(community, TODAY.minusDays(360), TODAY.plusDays(5));
        subscribe(community, TODAY.plusDays(5), TODAY.plusDays(370));

        service.execute(TODAY.plusDays(10), WARN_ONLY);

        assertThat(statusOfSubscription(old)).isEqualTo("EXPIRED");
        assertThat(emailsTo(owner, "subscription-expired")).isEmpty();
        assertThat(statusOfCommunity(community)).isEqualTo("ACTIVE");
    }

    @Test
    void warnsButNeverSuspendsByDefault() {
        assertThat(properties.autoSuspendEnabled()).as("default must be off").isFalse();
        UUID overdue = activeCommunity();
        subscribe(overdue, TODAY.minusDays(400), TODAY.minusDays(30));

        var report = service.execute(TODAY, Policy.from(properties));

        assertThat(statusOfCommunity(overdue)).isEqualTo("ACTIVE");
        assertThat(report.autoSuspended()).isZero();
        assertThat(report.overdueNotSuspended()).isGreaterThanOrEqualTo(1);
        assertThat(emailsTo(ownerEmail(overdue), "community-suspended")).isEmpty();
    }

    @Test
    void autoSuspendsAfterTheGracePeriodOnlyWhenEnabled() {
        UUID overdue = activeCommunity();
        subscribe(overdue, TODAY.minusDays(400), TODAY.minusDays(30));
        UUID inGrace = activeCommunity();
        subscribe(inGrace, TODAY.minusDays(400), TODAY.minusDays(3));
        UUID renewed = activeCommunity();
        subscribe(renewed, TODAY.minusDays(400), TODAY.minusDays(30));
        subscribe(renewed, TODAY.minusDays(30), TODAY.plusDays(335));
        UUID neverSubscribed = activeCommunity();

        var report = service.execute(TODAY, AUTO_SUSPEND);

        assertThat(report.autoSuspended()).isGreaterThanOrEqualTo(1);
        assertThat(statusOfCommunity(overdue)).isEqualTo("SUSPENDED");
        assertThat(statusOfCommunity(inGrace)).isEqualTo("ACTIVE");
        assertThat(statusOfCommunity(renewed)).isEqualTo("ACTIVE");
        assertThat(statusOfCommunity(neverSubscribed)).as("no subscription history, nothing to expire").isEqualTo("ACTIVE");
        assertThat(emailsTo(ownerEmail(overdue), "community-suspended")).hasSize(1);
        assertThat(jdbc.queryForObject("select status_reason from communities where id = ?", String.class, overdue)).contains("grace");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'COMMUNITY_SUSPENDED' and community_id = ? and actor_user_id is null", Long.class, overdue)).isOne();

        service.execute(TODAY, AUTO_SUSPEND);
        assertThat(emailsTo(ownerEmail(overdue), "community-suspended")).as("already suspended, so not repeated").hasSize(1);
    }

    @Test
    void oneFailureDoesNotStopTheRest() {
        UUID noOwner = data.communityOn(data.plan("STARTER")).getId();
        subscribe(noOwner, TODAY.minusDays(360), TODAY.plusDays(1));
        UUID fine = activeCommunity();
        subscribe(fine, TODAY.minusDays(360), TODAY.plusDays(1));

        service.execute(TODAY, WARN_ONLY);

        assertThat(emailsTo(ownerEmail(fine), "subscription-expiring")).hasSize(1);
    }

    @Test
    void theScheduledJobRunsUnderAShedLock() {
        UUID community = activeCommunity();
        String owner = ownerEmail(community);
        subscribe(community, TODAY.minusDays(364), TODAY.plusDays(1));

        // Another instance holds the lock: this one must skip the run.
        Optional<SimpleLock> held = lockProvider.lock(new LockConfiguration(clock.instant(), "subscriptionExpiryJob", Duration.ofMinutes(5), Duration.ZERO));
        assertThat(held).as("lock should be free at the start").isPresent();
        try {
            job.run();
            assertThat(emailsTo(owner, "subscription-expiring")).as("skipped while locked").isEmpty();
        } finally {
            held.get().unlock();
        }

        job.run();
        assertThat(emailsTo(owner, "subscription-expiring")).as("runs once the lock is free").hasSize(1);

        // lockAtLeastFor keeps a second instance from re-running it straight away.
        Optional<SimpleLock> again = lockProvider.lock(new LockConfiguration(clock.instant(), "subscriptionExpiryJob", Duration.ofMinutes(5), Duration.ZERO));
        assertThat(again).as("the lock is still held after the run").isEmpty();
    }

    @Test
    void theJobIsScheduledForNineAmIndiaTime() throws Exception {
        var scheduled = SubscriptionExpiryJob.class.getMethod("run").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 0 9 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Kolkata");
    }
}

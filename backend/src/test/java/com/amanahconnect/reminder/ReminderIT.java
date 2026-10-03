package com.amanahconnect.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.mail.AbstractMailIT;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.ApiClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReminderIT extends AbstractMailIT {

    @Autowired ReminderService reminders;
    @Autowired ReminderJobs jobs;
    @Autowired LockProvider lockProvider;

    private String address(UUID member) {
        return jdbc.queryForObject("select email::text from members where id = ?", String.class, member);
    }

    private UUID payerA(String name) {
        return member(sessionA, name, email(), true);
    }

    /** An issued invoice that was issued days ago (a reminder is never sent on the day an invoice is issued). */
    private UUID invoice(UUID member, String amount, LocalDate due) {
        UUID id = id(invoiceA(member, amount, due));
        jdbc.update("update invoices set issued_on = issued_on - 5 where id = ?", id);
        return id;
    }

    private UUID overdueInvoice(UUID member, String amount, int daysOverdue) {
        UUID id = invoice(member, amount, TODAY.plusDays(30));
        jdbc.update("update invoices set due_date = ?, status = 'OVERDUE' where id = ?", TODAY.minusDays(daysOverdue), id);
        return id;
    }

    private void settings(UUID communityId, int daysBefore, int everyDays) {
        jdbc.update("update notification_settings set due_reminder_days_before = ?, overdue_reminder_every_days = ? where community_id = ?", daysBefore, everyDays, communityId);
    }

    private List<Map<String, Object>> mails(String to, String template) {
        return jdbc.queryForList("select * from email_outbox where to_email = ? and template = ? order by created_at", to, template);
    }

    private long reminderRows(UUID invoice, String kind) {
        return count("select count(*) from invoice_reminders where invoice_id = ? and kind = ?", invoice, kind);
    }

    // ---- due soon ----------------------------------------------------------------------------------------------------------

    @Test
    void remindsAnInvoiceThatFallsDueWithinTheConfiguredDays() {
        UUID m = payerA("Soon");
        UUID inThree = invoice(m, "500.00", TODAY.plusDays(3));
        UUID inFour = invoice(m, "600.00", TODAY.plusDays(4));
        UUID today = invoice(m, "700.00", TODAY);

        ReminderService.Report report = reminders.run(TODAY);

        assertThat(report.dueSoon()).isGreaterThanOrEqualTo(2);
        assertThat(reminderRows(inThree, "DUE_SOON")).isEqualTo(1);
        assertThat(reminderRows(today, "DUE_SOON")).as("due today counts").isEqualTo(1);
        assertThat(reminderRows(inFour, "DUE_SOON")).as("a day too early").isZero();
        List<Map<String, Object>> mails = mails(address(m), "payment-reminder");
        assertThat(mails).hasSize(2);
        assertThat(mails).allSatisfy(mail -> assertThat(mail.get("community_id").toString()).isEqualTo(communityA.getId().toString()));
        assertThat(mails.stream().map(mail -> mail.get("payload").toString())).anyMatch(p -> p.contains("\"balance\": \"500.00\"") && p.contains(TODAY.plusDays(3).toString()) && p.contains(communityA.getName()));
    }

    @Test
    void followsTheCommunitysOwnSetting() {
        UUID m = payerA("Setting");
        UUID inSeven = invoice(m, "500.00", TODAY.plusDays(7));
        settings(communityA.getId(), 3, 7);
        reminders.run(TODAY);
        assertThat(reminderRows(inSeven, "DUE_SOON")).isZero();

        settings(communityA.getId(), 7, 7);
        reminders.run(TODAY);

        assertThat(reminderRows(inSeven, "DUE_SOON")).isEqualTo(1);
    }

    @Test
    void neverOnTheDayTheInvoiceWasIssued() {
        UUID m = payerA("Fresh");
        UUID fresh = id(invoiceA(m, "500.00", TODAY.plusDays(2))); // issued today

        reminders.run(TODAY);
        assertThat(reminderRows(fresh, "DUE_SOON")).isZero();

        reminders.run(TODAY.plusDays(1));
        assertThat(reminderRows(fresh, "DUE_SOON")).as("tomorrow it qualifies").isEqualTo(1);
    }

    @Test
    void aDueSoonReminderIsSentOncePerInvoiceNotEveryDay() {
        UUID m = payerA("Once");
        UUID inv = invoice(m, "500.00", TODAY.plusDays(3));

        reminders.run(TODAY);
        reminders.run(TODAY.plusDays(1));
        reminders.run(TODAY.plusDays(2));

        assertThat(reminderRows(inv, "DUE_SOON")).isEqualTo(1);
        assertThat(mails(address(m), "payment-reminder")).hasSize(1);
    }

    @Test
    void aMissedDayIsCaughtUpTheNextTime() {
        UUID m = payerA("Catch up");
        UUID inv = invoice(m, "500.00", TODAY.plusDays(3));

        reminders.run(TODAY.plusDays(2)); // the job did not run on the days before

        assertThat(reminderRows(inv, "DUE_SOON")).isEqualTo(1);
    }

    // ---- overdue -----------------------------------------------------------------------------------------------------------

    @Test
    void anOverdueInvoiceGetsANoticeThenAgainEveryConfiguredDays() {
        UUID m = payerA("Late");
        UUID inv = overdueInvoice(m, "800.00", 1);
        settings(communityA.getId(), 3, 7);

        reminders.run(TODAY);
        assertThat(mails(address(m), "overdue-notice")).as("the first day it is overdue").hasSize(1);
        assertThat(mails(address(m), "overdue-notice").get(0).get("payload").toString()).contains("\"daysOverdue\": 1");

        for (int day = 1; day <= 6; day++) reminders.run(TODAY.plusDays(day));
        assertThat(mails(address(m), "overdue-notice")).as("not again within 7 days").hasSize(1);

        reminders.run(TODAY.plusDays(7));
        assertThat(mails(address(m), "overdue-notice")).hasSize(2);
        reminders.run(TODAY.plusDays(14));
        assertThat(mails(address(m), "overdue-notice")).hasSize(3);
        assertThat(reminderRows(inv, "OVERDUE")).isEqualTo(3);
    }

    @Test
    void theOverdueIntervalIsTheCommunitys() {
        UUID m = payerA("Often");
        overdueInvoice(m, "800.00", 1);
        settings(communityA.getId(), 3, 2);

        for (int day = 0; day <= 6; day++) reminders.run(TODAY.plusDays(day));

        assertThat(mails(address(m), "overdue-notice")).as("days 0, 2, 4 and 6").hasSize(4);
    }

    // ---- idempotent per invoice and day -----------------------------------------------------------------------------------

    @Test
    void runningTheJobTwiceOnTheSameDayQueuesEachReminderOnce() {
        UUID m = payerA("Twice");
        UUID soon = invoice(m, "500.00", TODAY.plusDays(2));
        UUID late = overdueInvoice(m, "300.00", 2);

        reminders.run(TODAY);
        ReminderService.Report second = reminders.run(TODAY);
        reminders.run(TODAY);

        assertThat(second.dueSoon() + second.overdue()).isZero();
        assertThat(mails(address(m), "payment-reminder")).hasSize(1);
        assertThat(mails(address(m), "overdue-notice")).hasSize(1);
        assertThat(reminderRows(soon, "DUE_SOON")).isEqualTo(1);
        assertThat(reminderRows(late, "OVERDUE")).isEqualTo(1);
    }

    @Test
    void severalInstancesAtOnceStillQueueEachReminderOnce() throws Exception {
        List<UUID> members = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID m = payerA("Parallel " + i);
            members.add(m);
            invoice(m, "100.00", TODAY.plusDays(1));
            overdueInvoice(m, "50.00", 3);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Object>> runs = new ArrayList<>();
            for (int i = 0; i < 4; i++) runs.add(() -> reminders.run(TODAY));
            for (Future<Object> f : pool.invokeAll(runs)) f.get();
        } finally {
            pool.shutdownNow();
        }

        for (UUID m : members) {
            assertThat(mails(address(m), "payment-reminder")).as("due soon for " + m).hasSize(1);
            assertThat(mails(address(m), "overdue-notice")).as("overdue for " + m).hasSize(1);
        }
    }

    @Test
    void theClaimIsUniqueInTheDatabase() {
        UUID m = payerA("Unique");
        UUID inv = invoice(m, "100.00", TODAY.plusDays(1));
        jdbc.update("insert into invoice_reminders (community_id, invoice_id, kind, reminder_date) values (?, ?, 'OVERDUE', ?)", communityA.getId(), inv, TODAY);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("insert into invoice_reminders (community_id, invoice_id, kind, reminder_date) values (?, ?, 'OVERDUE', ?)", communityA.getId(), inv, TODAY))
                .hasMessageContaining("uq_invoice_reminders");
    }

    // ---- who is not reminded -----------------------------------------------------------------------------------------------

    @Test
    void settledOrInactiveInvoicesAndMembersAreLeftAlone() {
        UUID m = payerA("Skipped");
        UUID paid = invoice(m, "100.00", TODAY.plusDays(1));
        payOk(sessionA, paid, "100.00");
        UUID cancelled = invoice(m, "100.00", TODAY.plusDays(1));
        jdbc.update("update invoices set status = 'CANCELLED', cancel_reason = 't', cancelled_at = now() where id = ?", cancelled);
        ApiClient.Response drafted = asA("POST", INVOICES, new java.util.LinkedHashMap<>(Map.of("memberId", m.toString(), "kind", "MAINTENANCE", "description", "Draft", "amount", "100.00", "dueDate", TODAY.plusDays(1).toString(), "draft", true)));
        assertThat(drafted.status()).isEqualTo(201);
        UUID draft = id(drafted.json());
        UUID paidOverdue = overdueInvoice(m, "100.00", 2);
        jdbc.update("update invoices set amount_paid = amount, status = 'PAID' where id = ?", paidOverdue);
        UUID inactive = payerA("Inactive");
        UUID inactiveInvoice = invoice(inactive, "100.00", TODAY.plusDays(1));
        jdbc.update("update members set status = 'INACTIVE' where id = ?", inactive);
        UUID gone = payerA("Deleted");
        UUID goneInvoice = invoice(gone, "100.00", TODAY.plusDays(1));
        jdbc.update("update members set deleted_at = now() where id = ?", gone);

        reminders.run(TODAY);

        for (UUID invoice : List.of(paid, cancelled, draft, paidOverdue, inactiveInvoice, goneInvoice)) {
            assertThat(reminderRows(invoice, "DUE_SOON") + reminderRows(invoice, "OVERDUE")).as(invoice.toString()).isZero();
        }
    }

    @Test
    void aPartlyPaidInvoiceIsRemindedForWhatIsLeft() {
        UUID m = payerA("Partial");
        UUID inv = invoice(m, "1000.00", TODAY.plusDays(2));
        payOk(sessionA, inv, "400.00");

        reminders.run(TODAY);

        assertThat(mails(address(m), "payment-reminder").get(0).get("payload").toString()).contains("\"balance\": \"600.00\"");
    }

    @Test
    void aMemberWithoutAnAddressOrConsentGetsNothingAndIsRetriedLater() {
        UUID noConsent = member(sessionA, "No consent", email(), false);
        UUID noAddress = member(sessionA, "No address", null, true);
        UUID a = invoice(noConsent, "100.00", TODAY.plusDays(1));
        UUID b = invoice(noAddress, "100.00", TODAY.plusDays(1));

        ReminderService.Report report = reminders.run(TODAY);

        assertThat(report.skipped()).isGreaterThanOrEqualTo(2);
        assertThat(reminderRows(a, "DUE_SOON") + reminderRows(b, "DUE_SOON")).as("no claim is left behind").isZero();
        assertThat(mails(address(noConsent), "payment-reminder")).isEmpty();

        jdbc.update("update members set consent_email = true where id = ?", noConsent);
        reminders.run(TODAY);
        assertThat(mails(address(noConsent), "payment-reminder")).as("once they agree, the reminder goes out").hasSize(1);
    }

    @Test
    void theEmailQuotaStopsRemindersAndTheyGoOutOnceThereIsRoom() {
        UUID m = payerA("Quota");
        UUID inv = invoice(m, "100.00", TODAY.plusDays(1));
        long queued = count("select count(*) from email_outbox where community_id = ?", communityA.getId());
        Plan none = data.customPlan("No emails left", Map.of("emails_per_month", queued), Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", none.getId(), communityA.getId());

        reminders.run(TODAY);
        assertThat(mails(address(m), "payment-reminder")).isEmpty();
        assertThat(reminderRows(inv, "DUE_SOON")).isZero();

        Plan room = data.customPlan("Room", Map.of("emails_per_month", queued + 5), Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", room.getId(), communityA.getId());
        reminders.run(TODAY);

        assertThat(mails(address(m), "payment-reminder")).hasSize(1);
    }

    @Test
    void aSuspendedCommunityIsSkippedAndOthersAreNot() {
        UUID mine = payerA("Suspended");
        invoice(mine, "100.00", TODAY.plusDays(1));
        UUID theirs = member(sessionB, "Active elsewhere", email(), true);
        UUID theirInvoice = id(invoice(sessionB, theirs, "100.00", TODAY.plusDays(1)));
        jdbc.update("update invoices set issued_on = issued_on - 5 where id = ?", theirInvoice);
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());
        try {
            reminders.run(TODAY);
        } finally {
            jdbc.update("update communities set status = 'ACTIVE' where id = ?", communityA.getId());
        }

        assertThat(mails(address(mine), "payment-reminder")).isEmpty();
        assertThat(jdbc.queryForObject("select email::text from members where id = ?", String.class, theirs)).satisfies(a -> assertThat(mails(a, "payment-reminder")).hasSize(1));
    }

    @Test
    void eachCommunityFollowsItsOwnSettings() {
        UUID mineSoon = payerA("A person");
        UUID aInvoice = invoice(mineSoon, "100.00", TODAY.plusDays(5));
        UUID theirs = member(sessionB, "B person", email(), true);
        UUID bInvoice = id(invoice(sessionB, theirs, "100.00", TODAY.plusDays(5)));
        jdbc.update("update invoices set issued_on = issued_on - 5 where id = ?", bInvoice);
        settings(communityA.getId(), 3, 7);
        settings(communityB.getId(), 5, 7);

        reminders.run(TODAY);

        assertThat(reminderRows(aInvoice, "DUE_SOON")).isZero();
        assertThat(reminderRows(bInvoice, "DUE_SOON")).isEqualTo(1);
    }

    // ---- content and delivery ---------------------------------------------------------------------------------------------

    @Test
    void theReminderCarriesAFreshPaymentLinkWhenUpiIsSetUp() {
        setUpi(sessionA);
        UUID m = payerA("Pays by UPI");
        UUID inv = invoice(m, "250.00", TODAY.plusDays(1));
        long links = count("select count(*) from payment_links where invoice_id = ?", inv);

        reminders.run(TODAY);

        String payload = mails(address(m), "payment-reminder").get(0).get("payload").toString();
        assertThat(payload).contains("/pay/");
        assertThat(count("select count(*) from payment_links where invoice_id = ?", inv)).isEqualTo(links + 1);
    }

    @Test
    void withoutUpiThereIsNoLink() {
        UUID m = payerA("No UPI");
        invoice(m, "250.00", TODAY.plusDays(1));

        reminders.run(TODAY);

        assertThat(mails(address(m), "payment-reminder").get(0).get("payload").toString()).contains("\"payLink\": null");
    }

    @Test
    void theQueuedReminderIsSentAndRendered() {
        setUpi(sessionA);
        UUID m = payerA("Gets it");
        UUID inv = invoice(m, "250.00", TODAY.plusDays(1));
        UUID late = overdueInvoice(m, "75.00", 4);
        reminders.run(TODAY);

        sender.runOnce();

        var sent = smtp.sentTo(address(m)).stream().filter(mail -> List.of("payment-reminder", "overdue-notice").contains(mail.headers().get("X-Amanah-Template"))).toList();
        assertThat(sent).hasSize(2);
        assertThat(sent.stream().map(mail -> mail.subject())).anyMatch(s -> s.startsWith("Reminder: invoice")).anyMatch(s -> s.startsWith("Overdue: invoice"));
        var overdue = sent.stream().filter(mail -> mail.subject().startsWith("Overdue")).findFirst().orElseThrow();
        assertThat(overdue.html()).contains("Pay with UPI", "4").doesNotContain("null");
        assertThat(inv).isNotEqualTo(late);
    }

    @Test
    void theJobRunsDailyAtNineIndiaTimeUnderAShedLock() throws Exception {
        var scheduled = ReminderJobs.class.getMethod("remind").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        var lock = ReminderJobs.class.getMethod("remind").getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);
        assertThat(scheduled.cron()).isEqualTo("0 0 9 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Kolkata");
        assertThat(lock.name()).isEqualTo("invoiceReminderJob");

        UUID m = payerA("By job");
        invoice(m, "100.00", TODAY.plusDays(1));
        var held = lockProvider.lock(new LockConfiguration(java.time.Instant.now(), "invoiceReminderJob", java.time.Duration.ofMinutes(5), java.time.Duration.ZERO));
        assertThat(held).isPresent();
        try {
            jobs.remind();
            assertThat(mails(address(m), "payment-reminder")).as("skipped while another instance holds the lock").isEmpty();
        } finally {
            held.get().unlock();
        }
        jobs.remind();
        assertThat(mails(address(m), "payment-reminder")).hasSize(1);
    }
}

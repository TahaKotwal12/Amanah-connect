package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.billing.BillingJobService.GenerationReport;
import com.amanahconnect.billing.BillingJobService.OverdueReport;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class BillingJobIT extends AbstractFinanceIT {

    @Autowired BillingJobService service;
    @Autowired BillingJobs jobs;
    @Autowired LockProvider lockProvider;
    @Autowired Clock clock;

    private String status(UUID invoice) {
        return jdbc.queryForObject("select status from invoices where id = ?", String.class, invoice);
    }

    /** An invoice the API issued, then pushed back in time so the sweep has something to find. */
    private UUID aged(UUID member, String amount, String status, int daysPastDue) {
        UUID id = id(invoiceA(member, amount, TODAY.plusDays(30)));
        jdbc.update("update invoices set due_date = ?, status = ? where id = ?", TODAY.minusDays(daysPastDue), status, id);
        return id;
    }

    // ---- overdue ---------------------------------------------------------------------------------------------------------

    @Test
    void flipsIssuedAndPartialInvoicesPastTheirDueDate() {
        UUID m = memberA("Overdue Person");
        UUID issued = aged(m, "100.00", "ISSUED", 1);
        UUID partial = aged(m, "200.00", "PARTIAL", 5);
        jdbc.update("update invoices set amount_paid = 50 where id = ?", partial);

        OverdueReport report = service.markOverdue(TODAY);

        assertThat(report.invoicesMarked()).isGreaterThanOrEqualTo(2);
        assertThat(status(issued)).isEqualTo("OVERDUE");
        assertThat(status(partial)).isEqualTo("OVERDUE");
    }

    @Test
    void leavesEverythingElseAlone() {
        UUID m = memberA("Not Overdue");
        UUID dueToday = aged(m, "100.00", "ISSUED", 0);
        UUID future = id(invoiceA(m, "100.00", TODAY.plusDays(2)));
        UUID paid = id(invoiceA(m, "100.00", TODAY.plusDays(30)));
        payOk(sessionA, paid, "100.00");
        jdbc.update("update invoices set due_date = ? where id = ?", TODAY.minusDays(9), paid);
        UUID cancelled = aged(m, "100.00", "ISSUED", 9);
        jdbc.update("update invoices set status = 'CANCELLED', cancel_reason = 'test', cancelled_at = now() where id = ?", cancelled);

        service.markOverdue(TODAY);

        assertThat(status(dueToday)).as("due today is not late yet").isEqualTo("ISSUED");
        assertThat(status(future)).isEqualTo("ISSUED");
        assertThat(status(paid)).isEqualTo("PAID");
        assertThat(status(cancelled)).isEqualTo("CANCELLED");
    }

    @Test
    void isIdempotentAndWritesOneAuditEntryPerCommunityPerRun() {
        UUID m = memberA("Twice");
        UUID invoice = aged(m, "100.00", "ISSUED", 2);

        service.markOverdue(TODAY);
        long audits = count("select count(*) from audit_logs where community_id = ? and action = 'INVOICES_MARKED_OVERDUE'", communityA.getId());
        long version = jdbc.queryForObject("select version from invoices where id = ?", Long.class, invoice);
        OverdueReport second = service.markOverdue(TODAY);

        assertThat(audits).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'INVOICES_MARKED_OVERDUE'", communityA.getId())).as("nothing new, nothing logged").isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from invoices where id = ?", Long.class, invoice)).isEqualTo(version);
        assertThat(second.invoicesMarked()).isGreaterThanOrEqualTo(0);
        assertThat(status(invoice)).isEqualTo("OVERDUE");
    }

    @Test
    void anOverdueInvoiceCanStillBePaidOff() {
        UUID m = memberA("Pays Late");
        UUID invoice = aged(m, "100.00", "ISSUED", 3);
        service.markOverdue(TODAY);

        payOk(sessionA, invoice, "40.00");
        assertThat(status(invoice)).as("still late while part-paid").isEqualTo("OVERDUE");
        payOk(sessionA, invoice, "60.00");
        assertThat(status(invoice)).isEqualTo("PAID");
    }

    // ---- recurring generation --------------------------------------------------------------------------------------------

    private UUID autoPlan(String name, boolean auto) {
        JsonNode plan = feePlan(sessionA, name, "500.00", "MONTHLY", Map.of("autoGenerate", auto));
        return id(plan);
    }

    private long invoicesOf(UUID plan) {
        return count("select count(*) from invoices where fee_plan_id = ?", plan);
    }

    @Test
    void billsOnlyPlansThatOptedIn() {
        memberA("Billed");
        UUID off = autoPlan("Manual only", false);
        UUID on = autoPlan("Automatic", true);
        // A plan created today is already marked as billed for this period, so billing starts next period.
        jdbc.update("update fee_plans set last_generated_period = null where id in (?, ?)", off, on);

        GenerationReport report = service.generateDue(TODAY);

        assertThat(report.plansBilled()).isGreaterThanOrEqualTo(1);
        assertThat(invoicesOf(on)).isPositive();
        assertThat(invoicesOf(off)).isZero();
    }

    @Test
    void isIdempotentAndGatedByTheLastGeneratedPeriod() {
        memberA("Once");
        UUID plan = autoPlan("Gate", true);
        jdbc.update("update fee_plans set last_generated_period = null where id = ?", plan);

        service.generateDue(TODAY);
        long first = invoicesOf(plan);
        service.generateDue(TODAY);
        service.generateDue(TODAY);

        assertThat(first).isPositive();
        assertThat(invoicesOf(plan)).isEqualTo(first);
        assertThat(jdbc.queryForObject("select last_generated_period from fee_plans where id = ?", String.class, plan)).isEqualTo(TODAY.toString().substring(0, 7));
    }

    @Test
    void aNewPlanDoesNotBillThePeriodItWasCreatedIn() {
        memberA("Fresh");
        UUID plan = autoPlan("Starts next month", true);

        service.generateDue(TODAY);

        assertThat(invoicesOf(plan)).isZero();
        service.generateDue(TODAY.plusMonths(1).withDayOfMonth(1));
        assertThat(invoicesOf(plan)).isPositive();
    }

    @Test
    void skipsInactivePlansAndSuspendedCommunities() {
        memberA("Skipped");
        UUID inactive = autoPlan("Inactive", true);
        UUID suspended = autoPlan("Suspended", true);
        jdbc.update("update fee_plans set last_generated_period = null where id in (?, ?)", inactive, suspended);
        jdbc.update("update fee_plans set active = false where id = ?", inactive);
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());
        try {
            service.generateDue(TODAY);
        } finally {
            jdbc.update("update communities set status = 'ACTIVE' where id = ?", communityA.getId());
        }

        assertThat(invoicesOf(inactive)).isZero();
        assertThat(invoicesOf(suspended)).isZero();
    }

    @Test
    void oneFailingPlanDoesNotStopTheOthers() {
        memberA("Resilient");
        UUID bad = autoPlan("Bad", true);
        UUID good = autoPlan("Good", true);
        jdbc.update("update fee_plans set last_generated_period = null where id in (?, ?)", bad, good);
        // A corrupt member filter makes just this plan blow up.
        jdbc.update("update fee_plans set applies_to = 'SELECTED', applies_to_filter = '{\"memberIds\": [\"not-a-uuid\"]}'::jsonb where id = ?", bad);

        GenerationReport report = service.generateDue(TODAY);

        assertThat(invoicesOf(good)).isPositive();
        assertThat(report.failures()).isGreaterThanOrEqualTo(1);
        assertThat(invoicesOf(bad)).isZero();
    }

    // ---- scheduling ------------------------------------------------------------------------------------------------------

    @Test
    void bothJobsRunUnderTheirOwnShedLock() {
        memberA("Locked");
        UUID invoice = aged(memberA("Lock Overdue"), "100.00", "ISSUED", 2);

        for (String name : List.of("invoiceOverdueJob", "invoiceGenerationJob")) {
            Optional<SimpleLock> held = lockProvider.lock(new LockConfiguration(clock.instant(), name, Duration.ofMinutes(5), Duration.ZERO));
            assertThat(held).as(name + " lock should start free").isPresent();
            try {
                if (name.equals("invoiceOverdueJob")) jobs.markOverdue();
                else jobs.generateRecurring();
                if (name.equals("invoiceOverdueJob")) assertThat(status(invoice)).as("skipped while another instance holds the lock").isEqualTo("ISSUED");
            } finally {
                held.get().unlock();
            }
        }

        jobs.markOverdue();
        assertThat(status(invoice)).isEqualTo("OVERDUE");
        Optional<SimpleLock> again = lockProvider.lock(new LockConfiguration(clock.instant(), "invoiceOverdueJob", Duration.ofMinutes(5), Duration.ZERO));
        assertThat(again).as("lockAtLeastFor keeps it held after the run").isEmpty();
    }

    @Test
    void theJobsAreScheduledInIndiaTime() throws Exception {
        var overdue = BillingJobs.class.getMethod("markOverdue").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        var generate = BillingJobs.class.getMethod("generateRecurring").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(overdue.cron()).isEqualTo("0 30 0 * * *");
        assertThat(generate.cron()).isEqualTo("0 0 6 * * *");
        assertThat(overdue.zone()).isEqualTo("Asia/Kolkata");
        assertThat(generate.zone()).isEqualTo("Asia/Kolkata");
    }
}

package com.amanahconnect.reminder;

import com.amanahconnect.billing.BillingEmails;
import com.amanahconnect.billing.Invoice;
import com.amanahconnect.billing.InvoiceRepository;
import com.amanahconnect.billing.InvoiceService;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.notification.NotificationSettings;
import com.amanahconnect.notification.NotificationSettingsRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily reminder sweep. For every ACTIVE community, following its own notification settings:
 * <ul>
 *   <li><b>Due soon</b>: an unpaid invoice due within {@code due_reminder_days_before} days gets one reminder (never on the day it was issued).</li>
 *   <li><b>Overdue</b>: an overdue invoice gets a notice the first day it is overdue and again every {@code overdue_reminder_every_days} days.</li>
 * </ul>
 * Only members who are active, have an address and agreed to email are reminded, within the community's email quota.
 *
 * <p><b>Idempotent per invoice and day.</b> A reminder is first claimed by inserting its (invoice, kind, day) row, which has a unique key; only
 * the run that inserted it queues the email, so repeating the job, or running it on two instances at once, sends each reminder once. If the
 * email turns out not to be allowed (no consent, no quota) the claim is released so a later run can try again.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final int MAX_PER_COMMUNITY = 5000;

    public record Report(int communities, int dueSoon, int overdue, int skipped, int failures) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final CommunityRepository communities;
    private final NotificationSettingsRepository settings;
    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;
    private final BillingEmails billingEmails;

    public ReminderService(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager tm, CommunityRepository communities, NotificationSettingsRepository settings,
                           InvoiceRepository invoices, InvoiceService invoiceService, BillingEmails billingEmails) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(tm);
        this.communities = communities;
        this.settings = settings;
        this.invoices = invoices;
        this.invoiceService = invoiceService;
        this.billingEmails = billingEmails;
    }

    public Report run(LocalDate today) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM communities WHERE status = 'ACTIVE' ORDER BY id", new MapSqlParameterSource(), UUID.class);
        int[] total = new int[4];
        int failures = 0;
        for (UUID id : ids) {
            try {
                int[] one = transaction.execute(status -> remindCommunity(id, today));
                for (int i = 0; i < 3; i++) total[i] += one[i];
            } catch (RuntimeException e) {
                failures++;
                log.error("Reminders failed for community {}: {}", id, e.toString());
            }
        }
        Report report = new Report(ids.size(), total[0], total[1], total[2], failures);
        log.info("Reminder sweep for {}: {}", today, report);
        return report;
    }

    /** @return {dueSoon queued, overdue queued, skipped} */
    private int[] remindCommunity(UUID communityId, LocalDate today) {
        Community community = communities.findById(communityId).orElseThrow();
        NotificationSettings prefs = settings.findByCommunityId(communityId).orElse(null);
        int daysBefore = prefs == null ? 3 : prefs.getDueReminderDaysBefore();
        int everyDays = prefs == null ? 7 : prefs.getOverdueReminderEveryDays();
        BillingEmails.Session session = billingEmails.session(community);
        int[] counts = new int[3];

        for (UUID id : dueSoon(communityId, today, daysBefore)) {
            remind(community, session, id, ReminderKind.DUE_SOON, today, counts, 0);
        }
        for (UUID id : overdue(communityId, today, everyDays)) {
            Invoice invoice = invoices.findByIdAndCommunityId(id, communityId).orElse(null);
            if (invoice == null) continue;
            remind(community, session, id, ReminderKind.OVERDUE, today, counts, ChronoUnit.DAYS.between(invoice.getDueDate(), today));
        }
        return counts;
    }

    private void remind(Community community, BillingEmails.Session session, UUID invoiceId, ReminderKind kind, LocalDate today, int[] counts, long daysOverdue) {
        Invoice invoice = invoices.findByIdAndCommunityId(invoiceId, community.getId()).orElse(null);
        if (invoice == null || invoice.getAmount().compareTo(invoice.getAmountPaid()) <= 0) return; // paid since the list was read
        List<UUID> claimed = jdbc.queryForList(
                "INSERT INTO invoice_reminders (community_id, invoice_id, kind, reminder_date) VALUES (:c, :i, :k, :d) ON CONFLICT (invoice_id, kind, reminder_date) DO NOTHING RETURNING id",
                new MapSqlParameterSource("c", community.getId()).addValue("i", invoiceId).addValue("k", kind.name()).addValue("d", today), UUID.class);
        if (claimed.isEmpty()) return; // already reminded today
        BillingEmails.Outcome outcome = invoiceService.emailReminder(community, invoice, session, kind, daysOverdue);
        if (outcome == BillingEmails.Outcome.QUEUED) {
            counts[kind == ReminderKind.DUE_SOON ? 0 : 1]++;
        } else {
            jdbc.update("DELETE FROM invoice_reminders WHERE id = :id", new MapSqlParameterSource("id", claimed.get(0)));
            counts[2]++;
        }
    }

    private List<UUID> dueSoon(UUID communityId, LocalDate today, int daysBefore) {
        return jdbc.queryForList(
                "SELECT i.id FROM invoices i JOIN members m ON m.id = i.member_id AND m.community_id = i.community_id"
                        + " WHERE i.community_id = :c AND i.status IN ('ISSUED', 'PARTIAL') AND i.amount > i.amount_paid"
                        + " AND i.due_date >= :today AND i.due_date <= :horizon AND i.issued_on < :today"
                        + " AND m.status = 'ACTIVE' AND m.deleted_at IS NULL"
                        + " AND NOT EXISTS (SELECT 1 FROM invoice_reminders r WHERE r.invoice_id = i.id AND r.kind = 'DUE_SOON')"
                        + " ORDER BY i.due_date, i.id LIMIT " + MAX_PER_COMMUNITY,
                new MapSqlParameterSource("c", communityId).addValue("today", today).addValue("horizon", today.plusDays(daysBefore)), UUID.class);
    }

    private List<UUID> overdue(UUID communityId, LocalDate today, int everyDays) {
        return jdbc.queryForList(
                "SELECT i.id FROM invoices i JOIN members m ON m.id = i.member_id AND m.community_id = i.community_id"
                        + " WHERE i.community_id = :c AND i.status = 'OVERDUE' AND i.amount > i.amount_paid AND i.due_date < :today"
                        + " AND m.status = 'ACTIVE' AND m.deleted_at IS NULL"
                        + " AND coalesce((SELECT max(r.reminder_date) FROM invoice_reminders r WHERE r.invoice_id = i.id AND r.kind = 'OVERDUE'), DATE '0001-01-01') <= :lastAllowed"
                        + " ORDER BY i.due_date, i.id LIMIT " + MAX_PER_COMMUNITY,
                new MapSqlParameterSource("c", communityId).addValue("today", today).addValue("lastAllowed", today.minusDays(everyDays)), UUID.class);
    }
}

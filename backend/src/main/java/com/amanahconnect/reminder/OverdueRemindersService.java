package com.amanahconnect.reminder;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.billing.BillingEmails;
import com.amanahconnect.billing.Invoice;
import com.amanahconnect.billing.InvoiceRepository;
import com.amanahconnect.billing.InvoiceService;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The admin's "who owes us, and nudge them now". The list groups overdue invoices by member; the nudge sends an
 * overdue notice for every overdue invoice of an active member who has an address and agreed to email, within the
 * email quota. It uses the same claim as the daily sweep (one row per invoice, kind and day), so pressing it twice on
 * one day, or on the same day as the sweep, reminds each invoice once, and the sweep's spacing rule counts it.
 */
@Service
public class OverdueRemindersService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_INVOICES = 5000;

    /** One member who owes money past its due date. */
    public record DefaulterView(
            UUID memberId,
            String memberNo,
            String fullName,
            String email,
            long overdueInvoices,
            Money overdueAmount,
            LocalDate oldestDueDate,
            /** The most recent day an overdue notice was queued for any of their invoices, or null. */
            LocalDate lastRemindedOn) {}

    /**
     * @param overdueInvoices overdue invoices of active members considered
     * @param queued notices queued now
     * @param alreadyRemindedToday had a notice today already (nothing sent again)
     * @param skipped could not be emailed: no address, no consent or no quota left
     */
    public record RemindResult(int overdueInvoices, int queued, int alreadyRemindedToday, int skipped) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final CommunityRepository communities;
    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;
    private final BillingEmails billingEmails;
    private final AuditService audit;
    private final Clock clock;

    public OverdueRemindersService(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager tm,
            CommunityRepository communities,
            InvoiceRepository invoices,
            InvoiceService invoiceService,
            BillingEmails billingEmails,
            AuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(tm);
        this.communities = communities;
        this.invoices = invoices;
        this.invoiceService = invoiceService;
        this.billingEmails = billingEmails;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<DefaulterView> defaulters(UUID communityId, Pageable page) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", communityId);
        String from =
                " FROM invoices i JOIN members m ON m.id = i.member_id AND m.community_id = i.community_id"
                        + " WHERE i.community_id = :c AND i.status = 'OVERDUE' AND i.amount > i.amount_paid AND m.deleted_at IS NULL";
        Long total = jdbc.queryForObject("SELECT count(DISTINCT i.member_id)" + from, params, Long.class);
        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        List<DefaulterView> items = jdbc.query(
                "SELECT m.id AS member_id, m.member_no, m.full_name, m.email, count(*) AS n, sum(i.amount - i.amount_paid) AS owed,"
                        + " min(i.due_date) AS oldest,"
                        + " (SELECT max(r.reminder_date) FROM invoice_reminders r JOIN invoices ri ON ri.id = r.invoice_id"
                        + "   WHERE r.community_id = :c AND ri.member_id = m.id AND r.kind = 'OVERDUE') AS last_reminded"
                        + from
                        + " GROUP BY m.id, m.member_no, m.full_name, m.email ORDER BY owed DESC, m.member_no LIMIT :limit OFFSET :offset",
                params,
                (rs, row) -> new DefaulterView(
                        rs.getObject("member_id", UUID.class),
                        rs.getString("member_no"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getLong("n"),
                        Money.of(rs.getBigDecimal("owed")),
                        rs.getObject("oldest", LocalDate.class),
                        rs.getObject("last_reminded", LocalDate.class)));
        return new PageResponse<>(items, total == null ? 0 : total, page.getPageNumber(), page.getPageSize());
    }

    public RemindResult remindAllOverdue(UUID communityId) {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        int[] counts = new int[4]; // overdue, queued, already today, skipped
        transaction.executeWithoutResult(status -> {
            Community community = communities.findById(communityId).orElseThrow(NotFoundException::new);
            BillingEmails.Session session = billingEmails.session(community);
            List<UUID> ids = jdbc.queryForList(
                    "SELECT i.id FROM invoices i JOIN members m ON m.id = i.member_id AND m.community_id = i.community_id"
                            + " WHERE i.community_id = :c AND i.status = 'OVERDUE' AND i.amount > i.amount_paid AND i.due_date < :today"
                            + " AND m.status = 'ACTIVE' AND m.deleted_at IS NULL ORDER BY i.due_date, i.id LIMIT " + MAX_INVOICES,
                    new MapSqlParameterSource("c", communityId).addValue("today", today),
                    UUID.class);
            for (UUID id : ids) {
                Invoice invoice = invoices.findByIdAndCommunityId(id, communityId).orElse(null);
                if (invoice == null || invoice.getAmount().compareTo(invoice.getAmountPaid()) <= 0) {
                    continue;
                }
                counts[0]++;
                List<UUID> claimed = jdbc.queryForList(
                        "INSERT INTO invoice_reminders (community_id, invoice_id, kind, reminder_date) VALUES (:c, :i, :k, :d)"
                                + " ON CONFLICT (invoice_id, kind, reminder_date) DO NOTHING RETURNING id",
                        new MapSqlParameterSource("c", communityId).addValue("i", id).addValue("k", ReminderKind.OVERDUE.name()).addValue("d", today),
                        UUID.class);
                if (claimed.isEmpty()) {
                    counts[2]++;
                    continue;
                }
                BillingEmails.Outcome outcome = invoiceService.emailReminder(
                        community, invoice, session, ReminderKind.OVERDUE, ChronoUnit.DAYS.between(invoice.getDueDate(), today));
                if (outcome == BillingEmails.Outcome.QUEUED) {
                    counts[1]++;
                } else {
                    // Not allowed to email them (no address, no consent, no quota): release the claim for a later try.
                    jdbc.update("DELETE FROM invoice_reminders WHERE id = :id", new MapSqlParameterSource("id", claimed.get(0)));
                    counts[3]++;
                }
            }
        });
        RemindResult result = new RemindResult(counts[0], counts[1], counts[2], counts[3]);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("overdueInvoices", result.overdueInvoices());
        after.put("queued", result.queued());
        after.put("alreadyRemindedToday", result.alreadyRemindedToday());
        after.put("skipped", result.skipped());
        audit.record("OVERDUE_REMINDERS_SENT", AuditService.currentActorId(), communityId, "Community", communityId, null, after);
        return result;
    }
}

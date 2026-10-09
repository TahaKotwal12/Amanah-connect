package com.amanahconnect.dashboard;

import com.amanahconnect.audit.AuditDtos.CommunityEntry;
import com.amanahconnect.audit.AuditQueries;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.dashboard.DashboardDtos.Activity;
import com.amanahconnect.dashboard.DashboardDtos.Collection;
import com.amanahconnect.dashboard.DashboardDtos.Complaints;
import com.amanahconnect.dashboard.DashboardDtos.Dashboard;
import com.amanahconnect.dashboard.DashboardDtos.DueItem;
import com.amanahconnect.dashboard.DashboardDtos.Members;
import com.amanahconnect.dashboard.DashboardDtos.Overdue;
import com.amanahconnect.dashboard.DashboardDtos.TrendPoint;
import com.amanahconnect.dashboard.DashboardDtos.UpcomingDues;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The dashboard in four queries, however many members or invoices there are: one of scalar subqueries for every headline figure, one for the
 * soonest dues (joined to their members, no per-row lookups), one for the twelve-month trend (a generated series joined to two grouped
 * sums) and one for recent activity. Each is scoped by community id and served by an index: invoices by (community, issued_on) and
 * (community, due_date) for open ones, payments by (community, received_on), ledger and complaints by community, audit by (community, created_at, id).
 */
@Component
public class DashboardQueries {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int ACTIVITY = 10;
    private static final int DUE_ITEMS = 10;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditQueries audit;
    private final DashboardProperties properties;
    private final Clock clock;

    public DashboardQueries(NamedParameterJdbcTemplate jdbc, AuditQueries audit, DashboardProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    public Dashboard build(UUID communityId) {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        YearMonth month = YearMonth.from(today);
        LocalDate monthStart = month.atDay(1);
        LocalDate nextMonth = month.plusMonths(1).atDay(1);
        MapSqlParameterSource p = new MapSqlParameterSource("c", communityId)
                .addValue("today", today).addValue("monthStart", monthStart).addValue("nextMonth", nextMonth).addValue("horizon", today.plusDays(properties.upcomingDays()));

        Headline h = jdbc.queryForObject(
                "SELECT c.currency,"
                        + " (SELECT count(*) FROM members m WHERE m.community_id = c.id AND m.deleted_at IS NULL) AS members_total,"
                        + " (SELECT count(*) FROM members m WHERE m.community_id = c.id AND m.deleted_at IS NULL AND m.status = 'ACTIVE') AS members_active,"
                        + " (SELECT coalesce(sum(i.amount), 0) FROM invoices i WHERE i.community_id = c.id AND i.status NOT IN ('DRAFT', 'CANCELLED') AND i.issued_on >= :monthStart AND i.issued_on < :nextMonth) AS billed,"
                        + " (SELECT coalesce(sum(i.amount_paid), 0) FROM invoices i WHERE i.community_id = c.id AND i.status NOT IN ('DRAFT', 'CANCELLED') AND i.issued_on >= :monthStart AND i.issued_on < :nextMonth) AS collected,"
                        + " (SELECT coalesce(sum(r.amount), 0) FROM payment_records r WHERE r.community_id = c.id AND r.received_on >= :monthStart AND r.received_on < :nextMonth) AS received,"
                        + " (SELECT count(*) FROM invoices i WHERE i.community_id = c.id AND i.status = 'OVERDUE') AS overdue_count,"
                        + " (SELECT coalesce(sum(i.amount - i.amount_paid), 0) FROM invoices i WHERE i.community_id = c.id AND i.status = 'OVERDUE') AS overdue_amount,"
                        + " c.opening_balance + (SELECT coalesce(sum(CASE e.type WHEN 'INCOME' THEN e.amount ELSE -e.amount END), 0) FROM ledger_entries e WHERE e.community_id = c.id) AS net_balance,"
                        + " (SELECT count(*) FROM complaints k WHERE k.community_id = c.id AND k.status IN ('OPEN', 'IN_PROGRESS')) AS complaints_open,"
                        + " (SELECT count(*) FROM complaints k WHERE k.community_id = c.id AND k.status IN ('OPEN', 'IN_PROGRESS')"
                        + "    AND now() - k.created_at > make_interval(days => CASE k.priority WHEN 'URGENT' THEN 1 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 7 ELSE 14 END)) AS complaints_breached,"
                        + " (SELECT count(*) FROM support_messages s WHERE s.community_id = c.id AND s.sender_side = 'PLATFORM' AND s.read_at IS NULL) AS unread_support,"
                        + " (SELECT count(*) FROM invoices i WHERE i.community_id = c.id AND i.status IN ('ISSUED', 'PARTIAL') AND i.due_date >= :today AND i.due_date <= :horizon) AS due_count,"
                        + " (SELECT coalesce(sum(i.amount - i.amount_paid), 0) FROM invoices i WHERE i.community_id = c.id AND i.status IN ('ISSUED', 'PARTIAL') AND i.due_date >= :today AND i.due_date <= :horizon) AS due_amount"
                        + " FROM communities c WHERE c.id = :c",
                p, (rs, n) -> new Headline(rs.getString("currency"), rs.getLong("members_total"), rs.getLong("members_active"), rs.getBigDecimal("billed"), rs.getBigDecimal("collected"),
                        rs.getBigDecimal("received"), rs.getLong("overdue_count"), rs.getBigDecimal("overdue_amount"), rs.getBigDecimal("net_balance"), rs.getLong("complaints_open"),
                        rs.getLong("complaints_breached"), rs.getLong("unread_support"), rs.getLong("due_count"), rs.getBigDecimal("due_amount")));

        List<DueItem> items = jdbc.query(
                "SELECT i.id, i.invoice_no, i.member_id, m.full_name, i.due_date, i.amount - i.amount_paid AS balance, i.status"
                        + " FROM invoices i JOIN members m ON m.id = i.member_id AND m.community_id = i.community_id"
                        + " WHERE i.community_id = :c AND i.status IN ('ISSUED', 'PARTIAL') AND i.due_date >= :today AND i.due_date <= :horizon"
                        + " ORDER BY i.due_date, i.invoice_no, i.id LIMIT " + DUE_ITEMS,
                p, (rs, n) -> new DueItem(rs.getObject("id", UUID.class), rs.getString("invoice_no"), rs.getObject("member_id", UUID.class), rs.getString("full_name"),
                        rs.getObject("due_date", LocalDate.class), Money.of(rs.getBigDecimal("balance")), rs.getString("status")));

        List<TrendPoint> trend = jdbc.query(
                "SELECT to_char(s.month, 'YYYY-MM') AS month, coalesce(b.billed, 0) AS billed, coalesce(r.received, 0) AS received"
                        + " FROM generate_series(CAST(:monthStart AS date) - interval '11 months', CAST(:monthStart AS date), interval '1 month') AS s(month)"
                        + " LEFT JOIN (SELECT date_trunc('month', i.issued_on) AS month, sum(i.amount) AS billed FROM invoices i"
                        + "            WHERE i.community_id = :c AND i.status NOT IN ('DRAFT', 'CANCELLED') AND i.issued_on >= CAST(:monthStart AS date) - interval '11 months' AND i.issued_on < :nextMonth GROUP BY 1) b ON b.month = s.month"
                        + " LEFT JOIN (SELECT date_trunc('month', r.received_on) AS month, sum(r.amount) AS received FROM payment_records r"
                        + "            WHERE r.community_id = :c AND r.received_on >= CAST(:monthStart AS date) - interval '11 months' AND r.received_on < :nextMonth GROUP BY 1) r ON r.month = s.month"
                        + " ORDER BY s.month",
                p, (rs, n) -> new TrendPoint(rs.getString("month"), Money.of(rs.getBigDecimal("billed")), Money.of(rs.getBigDecimal("received"))));

        List<Activity> activity = audit.recentForCommunity(communityId, ACTIVITY).stream().map(DashboardQueries::activity).toList();

        return new Dashboard(clock.instant(), h.currency(), month.toString(),
                new Members(h.membersTotal(), h.membersActive()),
                new Collection(Money.of(h.billed()), Money.of(h.collected()), Money.of(h.billed().subtract(h.collected())), Money.of(h.received())),
                new Overdue(h.overdueCount(), Money.of(h.overdueAmount())),
                Money.of(h.netBalance()),
                new Complaints(h.complaintsOpen(), h.complaintsBreached()),
                h.unreadSupport(),
                new UpcomingDues(properties.upcomingDays(), h.dueCount(), Money.of(h.dueAmount()), items),
                activity, trend);
    }

    private static Activity activity(CommunityEntry e) {
        return new Activity(e.at(), e.summary(), e.actorName(), e.byPlatformStaff(), e.action());
    }

    private record Headline(String currency, long membersTotal, long membersActive, BigDecimal billed, BigDecimal collected, BigDecimal received, long overdueCount, BigDecimal overdueAmount,
                            BigDecimal netBalance, long complaintsOpen, long complaintsBreached, long unreadSupport, long dueCount, BigDecimal dueAmount) {}
}

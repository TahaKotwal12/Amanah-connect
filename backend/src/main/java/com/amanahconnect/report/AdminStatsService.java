package com.amanahconnect.report;

import com.amanahconnect.common.money.Money;
import com.amanahconnect.plan.SubscriptionProperties;
import com.amanahconnect.plan.SubscriptionStatusRules;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform-wide numbers for the super admin dashboard, from one statement of scalar aggregate subqueries
 * (a single round trip, each part an index-friendly count or sum, no rows loaded into memory).
 */
@Service
@Transactional(readOnly = true)
public class AdminStatsService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record Communities(long total, long pending, long active, long suspended, long archived) {}

    /** Revenue by the date the community paid (IST), cancelled payments excluded. Calendar month and calendar year. */
    public record Revenue(Money thisMonth, Money thisYear, String currency) {}

    public record Stats(
            Communities communities,
            long totalMembers,
            Revenue platformRevenue,
            long expiringSubscriptions,
            int expiringWithinDays,
            long newLeads,
            long openSupportThreads) {}

    /** One calendar month (IST): revenue by paid date, cancelled payments excluded; communities created that month. */
    public record MonthPoint(String month, Money revenue, long newCommunities) {}

    public record Monthly(String currency, java.util.List<MonthPoint> months) {}

    private static final int MAX_MONTHS = 36;

    private static final String MONTHLY_SQL =
            """
            SELECT to_char(m.month_start, 'YYYY-MM') AS month,
                   (SELECT coalesce(sum(s.amount), 0) FROM platform_subscriptions s
                     WHERE s.status <> 'CANCELLED' AND s.paid_on >= m.month_start
                       AND s.paid_on < (m.month_start + interval '1 month')::date) AS revenue,
                   (SELECT count(*) FROM communities c
                     WHERE (c.created_at AT TIME ZONE 'Asia/Kolkata')::date >= m.month_start
                       AND (c.created_at AT TIME ZONE 'Asia/Kolkata')::date < (m.month_start + interval '1 month')::date) AS new_communities
              FROM generate_series(CAST(:first AS date), CAST(:last AS date), interval '1 month') AS m(month_start)
             ORDER BY m.month_start
            """;

    private static final String SQL =
            """
            SELECT
              (SELECT count(*) FROM communities WHERE status <> 'ARCHIVED')                       AS total,
              (SELECT count(*) FROM communities WHERE status = 'PENDING')                         AS pending,
              (SELECT count(*) FROM communities WHERE status = 'ACTIVE')                          AS active,
              (SELECT count(*) FROM communities WHERE status = 'SUSPENDED')                       AS suspended,
              (SELECT count(*) FROM communities WHERE status = 'ARCHIVED')                        AS archived,
              (SELECT count(*) FROM members m JOIN communities c ON c.id = m.community_id
                WHERE m.deleted_at IS NULL AND c.status <> 'ARCHIVED')                            AS members,
              (SELECT coalesce(sum(amount), 0) FROM platform_subscriptions
                WHERE status <> 'CANCELLED' AND paid_on >= :monthStart AND paid_on < :nextMonthStart) AS revenue_month,
              (SELECT coalesce(sum(amount), 0) FROM platform_subscriptions
                WHERE status <> 'CANCELLED' AND paid_on >= :yearStart AND paid_on < :nextYearStart)   AS revenue_year,
              (SELECT count(*) FROM platform_subscriptions s
                WHERE s.status <> 'CANCELLED' AND (""" + SubscriptionStatusRules.DISPLAY_SQL + """
                ) = 'EXPIRING')                                                                   AS expiring,
              (SELECT count(*) FROM leads WHERE status = 'NEW')                                   AS new_leads,
              (SELECT count(*) FROM support_threads WHERE status IN ('OPEN', 'WAITING'))          AS open_threads
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final SubscriptionProperties properties;
    private final Clock clock;

    public AdminStatsService(NamedParameterJdbcTemplate jdbc, SubscriptionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public Stats stats() {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate yearStart = today.withDayOfYear(1);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("today", today)
                .addValue("windowEnd", today.plusDays(properties.expiringWindowDays()))
                .addValue("monthStart", monthStart)
                .addValue("nextMonthStart", monthStart.plusMonths(1))
                .addValue("yearStart", yearStart)
                .addValue("nextYearStart", yearStart.plusYears(1));
        return jdbc.queryForObject(SQL, params, (rs, row) -> new Stats(
                new Communities(rs.getLong("total"), rs.getLong("pending"), rs.getLong("active"), rs.getLong("suspended"), rs.getLong("archived")),
                rs.getLong("members"),
                new Revenue(Money.of(rs.getBigDecimal("revenue_month")), Money.of(rs.getBigDecimal("revenue_year")), "INR"),
                rs.getLong("expiring"),
                properties.expiringWindowDays(),
                rs.getLong("new_leads"),
                rs.getLong("open_threads")));
    }

    /** The last {@code months} calendar months ending with the current one, oldest first, zero-filled. */
    public Monthly monthly(int months) {
        int count = Math.max(1, Math.min(months, MAX_MONTHS));
        LocalDate last = LocalDate.now(clock.withZone(IST)).withDayOfMonth(1);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("first", last.minusMonths(count - 1L))
                .addValue("last", last);
        java.util.List<MonthPoint> points = jdbc.query(MONTHLY_SQL, params, (rs, row) ->
                new MonthPoint(rs.getString("month"), Money.of(rs.getBigDecimal("revenue")), rs.getLong("new_communities")));
        return new Monthly("INR", points);
    }
}

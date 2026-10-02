package com.amanahconnect.community.admin;

import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.admin.AdminCommunityDtos.ActivityLine;
import com.amanahconnect.community.admin.AdminCommunityDtos.CommunityListItem;
import com.amanahconnect.community.admin.AdminCommunityDtos.Counts;
import com.amanahconnect.community.admin.AdminCommunityDtos.FinanceSummary;
import com.amanahconnect.community.admin.AdminCommunityDtos.PlanRef;
import com.amanahconnect.plan.SubscriptionStatusRules;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-side SQL for the super-admin views. Plain aggregate queries instead of entity graphs: no N+1, a few
 * statements per page, and nothing here can modify data. Every value that reaches SQL is a bound parameter;
 * the only text spliced in is a column from a fixed whitelist.
 */
@Component
public class AdminCommunityQueries {

    /** API sort property to SQL expression. The whitelist in the controller guarantees only these arrive. */
    private static final Map<String, String> SORT_SQL =
            Map.of(
                    "name", "lower(c.name)",
                    "createdAt", "c.created_at",
                    "status", "c.status",
                    "plan", "p.name",
                    "memberCount", "member_count",
                    "subscriptionEndsOn", "sub.period_end");

    private final NamedParameterJdbcTemplate jdbc;

    public AdminCommunityQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Filter(String search, String status, String planCode) {}

    public PageResponse<CommunityListItem> list(Filter filter, PageRequest page, LocalDate today, int windowDays) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (filter.status() != null) {
            where.append(" AND c.status = :status");
            params.addValue("status", filter.status());
        }
        if (filter.planCode() != null) {
            where.append(" AND p.code = :planCode");
            params.addValue("planCode", filter.planCode());
        }
        if (filter.search() != null && !filter.search().isBlank()) {
            where.append(" AND (c.name ILIKE :q ESCAPE '\\' OR c.slug ILIKE :q ESCAPE '\\' OR c.contact_email::text ILIKE :q ESCAPE '\\' OR u.email::text ILIKE :q ESCAPE '\\')");
            params.addValue("q", "%" + escapeLike(filter.search().trim()) + "%");
        }
        String from =
                " FROM communities c JOIN plans p ON p.id = c.plan_id LEFT JOIN users u ON u.id = c.owner_user_id"
                        + " LEFT JOIN LATERAL (SELECT s.period_end, s.status FROM platform_subscriptions s"
                        + "   WHERE s.community_id = c.id AND s.status <> 'CANCELLED' ORDER BY s.period_end DESC LIMIT 1) sub ON true";

        Long total = jdbc.queryForObject("SELECT count(*)" + from + where, params, Long.class);

        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        String sql =
                "SELECT c.id, c.name, c.slug, c.status, c.created_at, p.id AS plan_id, p.code AS plan_code, p.name AS plan_name,"
                        + " u.full_name AS owner_name, u.email::text AS owner_email, sub.period_end, sub.status AS sub_status,"
                        + " (SELECT count(*) FROM members m WHERE m.community_id = c.id AND m.deleted_at IS NULL) AS member_count"
                        + from + where + orderBy(page.getSort()) + " LIMIT :limit OFFSET :offset";
        List<CommunityListItem> items =
                jdbc.query(
                        sql,
                        params,
                        (rs, row) -> {
                            Date end = rs.getDate("period_end");
                            LocalDate periodEnd = end == null ? null : end.toLocalDate();
                            return new CommunityListItem(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("name"),
                                    rs.getString("slug"),
                                    rs.getString("status"),
                                    new PlanRef(rs.getObject("plan_id", UUID.class), rs.getString("plan_code"), rs.getString("plan_name")),
                                    rs.getLong("member_count"),
                                    periodEnd,
                                    periodEnd == null ? "NONE" : SubscriptionStatusRules.display(rs.getString("sub_status"), periodEnd, today, windowDays, false),
                                    rs.getString("owner_name"),
                                    rs.getString("owner_email"),
                                    rs.getTimestamp("created_at").toInstant());
                        });
        return new PageResponse<>(items, total == null ? 0 : total, page.getPageNumber(), page.getPageSize());
    }

    private static String orderBy(Sort sort) {
        List<String> parts = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = order.getProperty().equals("id") ? "c.id" : SORT_SQL.get(order.getProperty());
            if (column == null) {
                throw new IllegalArgumentException("Sort property is not whitelisted: " + order.getProperty());
            }
            parts.add(column + (order.isDescending() ? " DESC" : " ASC") + " NULLS LAST");
        }
        return " ORDER BY " + String.join(", ", parts);
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ---- single-community aggregates -------------------------------------------------------------

    public long memberCount(UUID communityId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM members WHERE community_id = :c AND deleted_at IS NULL",
                new MapSqlParameterSource("c", communityId), Long.class);
        return count == null ? 0 : count;
    }

    /** Latest non-cancelled subscription: {period_end, stored status}, or null. */
    public Map<String, Object> latestSubscription(UUID communityId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT period_end, status FROM platform_subscriptions WHERE community_id = :c AND status <> 'CANCELLED' ORDER BY period_end DESC LIMIT 1",
                new MapSqlParameterSource("c", communityId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Counts counts(UUID communityId) {
        return jdbc.queryForObject(
                """
                SELECT
                  (SELECT count(*) FROM members WHERE community_id = :c AND deleted_at IS NULL AND status = 'ACTIVE') AS members_active,
                  (SELECT count(*) FROM members WHERE community_id = :c AND deleted_at IS NULL AND status = 'INACTIVE') AS members_inactive,
                  (SELECT count(*) FROM member_registrations WHERE community_id = :c AND status = 'PENDING') AS pending_registrations,
                  (SELECT count(*) FROM invoices WHERE community_id = :c AND status IN ('ISSUED', 'PARTIAL', 'OVERDUE')) AS open_invoices,
                  (SELECT count(*) FROM complaints WHERE community_id = :c AND status IN ('OPEN', 'IN_PROGRESS')) AS open_complaints,
                  (SELECT count(*) FROM support_threads WHERE community_id = :c AND status IN ('OPEN', 'WAITING')) AS open_support_threads
                """,
                new MapSqlParameterSource("c", communityId),
                (rs, row) -> new Counts(
                        rs.getLong("members_active"), rs.getLong("members_inactive"), rs.getLong("pending_registrations"),
                        rs.getLong("open_invoices"), rs.getLong("open_complaints"), rs.getLong("open_support_threads")));
    }

    public FinanceSummary finance(UUID communityId, LocalDate financialYearStart) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", communityId).addValue("fy", financialYearStart);
        return jdbc.queryForObject(
                """
                SELECT
                  coalesce((SELECT sum(amount) FROM invoices WHERE community_id = :c AND status IN ('ISSUED', 'PARTIAL', 'OVERDUE', 'PAID')), 0) AS invoiced,
                  coalesce((SELECT sum(amount_paid) FROM invoices WHERE community_id = :c AND status IN ('ISSUED', 'PARTIAL', 'OVERDUE', 'PAID')), 0) AS collected,
                  coalesce((SELECT sum(amount - amount_paid) FROM invoices WHERE community_id = :c AND status IN ('ISSUED', 'PARTIAL', 'OVERDUE')), 0) AS outstanding,
                  coalesce((SELECT sum(amount) FROM ledger_entries WHERE community_id = :c AND type = 'INCOME' AND entry_date >= :fy), 0) AS income,
                  coalesce((SELECT sum(amount) FROM ledger_entries WHERE community_id = :c AND type = 'EXPENSE' AND entry_date >= :fy), 0) AS expense
                """,
                params,
                (rs, row) -> {
                    BigDecimal income = rs.getBigDecimal("income");
                    BigDecimal expense = rs.getBigDecimal("expense");
                    return new FinanceSummary(
                            Money.of(rs.getBigDecimal("invoiced")), Money.of(rs.getBigDecimal("collected")), Money.of(rs.getBigDecimal("outstanding")),
                            Money.of(income), Money.of(expense), Money.of(income.subtract(expense)), financialYearStart);
                });
    }

    /** Recent audit lines for the community. Deliberately omits before/after (they can hold personal data). */
    public List<ActivityLine> recentActivity(UUID communityId, int limit) {
        return jdbc.query(
                """
                SELECT a.id, a.action, a.entity_type, a.entity_id, a.actor_user_id, u.full_name AS actor_name, a.created_at
                FROM audit_logs a LEFT JOIN users u ON u.id = a.actor_user_id
                WHERE a.community_id = :c ORDER BY a.created_at DESC, a.id DESC LIMIT :limit
                """,
                new MapSqlParameterSource("c", communityId).addValue("limit", limit),
                (rs, row) -> new ActivityLine(
                        rs.getObject("id", UUID.class), rs.getString("action"), rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class), rs.getObject("actor_user_id", UUID.class),
                        rs.getString("actor_name"),
                        rs.getTimestamp("created_at").toInstant()));
    }
}

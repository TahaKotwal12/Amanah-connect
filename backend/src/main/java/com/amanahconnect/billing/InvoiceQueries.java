package com.amanahconnect.billing;

import com.amanahconnect.billing.BillingDtos.InvoiceView;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.page.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Searching a community's invoices. Plain SQL, always scoped by community_id, with a whitelist for sorting. */
@Component
public class InvoiceQueries {

    public record InvoiceFilter(InvoiceStatus status, UUID memberId, UUID feePlanId, FeeKind kind, String period, LocalDate dueFrom, LocalDate dueTo, String q, boolean outstandingOnly) {}

    private static final Map<String, String> SORT_SQL = Map.of(
            "dueDate", "i.due_date", "issuedOn", "i.issued_on", "amount", "i.amount", "invoiceNo", "i.invoice_no",
            "status", "i.status", "createdAt", "i.created_at", "memberName", "lower(m.full_name)");

    private static final String SELECT =
            "SELECT i.id, i.invoice_no, i.status, i.kind, i.member_id, m.member_no, m.full_name, i.fee_plan_id, i.period,"
                    + " coalesce(i.description, fp.name) AS description, i.amount, i.amount_paid, i.issued_on, i.due_date,"
                    + " i.cancel_reason, i.cancelled_at, i.version, i.created_at"
                    + " FROM invoices i LEFT JOIN members m ON m.id = i.member_id AND m.community_id = i.community_id"
                    + " LEFT JOIN fee_plans fp ON fp.id = i.fee_plan_id AND fp.community_id = i.community_id";

    private final NamedParameterJdbcTemplate jdbc;

    public InvoiceQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<InvoiceView> search(UUID communityId, InvoiceFilter f, Pageable page) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", communityId);
        StringBuilder where = new StringBuilder(" WHERE i.community_id = :c");
        if (f.status() != null) { where.append(" AND i.status = :status"); params.addValue("status", f.status().name()); }
        if (f.memberId() != null) { where.append(" AND i.member_id = :member"); params.addValue("member", f.memberId()); }
        if (f.feePlanId() != null) { where.append(" AND i.fee_plan_id = :plan"); params.addValue("plan", f.feePlanId()); }
        if (f.kind() != null) { where.append(" AND i.kind = :kind"); params.addValue("kind", f.kind().name()); }
        if (f.period() != null && !f.period().isBlank()) { where.append(" AND i.period = :period"); params.addValue("period", f.period().trim()); }
        if (f.dueFrom() != null) { where.append(" AND i.due_date >= :dueFrom"); params.addValue("dueFrom", f.dueFrom()); }
        if (f.dueTo() != null) { where.append(" AND i.due_date <= :dueTo"); params.addValue("dueTo", f.dueTo()); }
        if (f.outstandingOnly()) where.append(" AND i.status IN ('ISSUED', 'PARTIAL', 'OVERDUE')");
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (lower(coalesce(i.invoice_no, '')) LIKE :q ESCAPE '\\' OR lower(m.full_name) LIKE :q ESCAPE '\\' OR lower(m.member_no) LIKE :q ESCAPE '\\')");
            params.addValue("q", "%" + f.q().trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM invoices i LEFT JOIN members m ON m.id = i.member_id AND m.community_id = i.community_id" + where, params, Long.class);
        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        var items = jdbc.query(SELECT + where + orderBy(page.getSort()) + " LIMIT :limit OFFSET :offset", params, (rs, row) -> map(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page.getPageNumber(), page.getPageSize());
    }

    private static String orderBy(Sort sort) {
        StringBuilder out = new StringBuilder(" ORDER BY ");
        boolean any = false;
        for (Sort.Order order : sort) {
            String column = SORT_SQL.get(order.getProperty());
            if (column == null) continue;
            out.append(any ? ", " : "").append(column).append(order.isAscending() ? " ASC" : " DESC");
            any = true;
        }
        if (!any) out.append("i.created_at DESC");
        return out.append(", i.id").toString();
    }

    static InvoiceView map(ResultSet rs) throws SQLException {
        Money amount = Money.of(rs.getBigDecimal("amount"));
        Money paid = Money.of(rs.getBigDecimal("amount_paid"));
        return new InvoiceView(
                rs.getObject("id", UUID.class), rs.getString("invoice_no"), InvoiceStatus.valueOf(rs.getString("status")), FeeKind.valueOf(rs.getString("kind")),
                rs.getObject("member_id", UUID.class), rs.getString("member_no"), rs.getString("full_name"), rs.getObject("fee_plan_id", UUID.class),
                rs.getString("period"), rs.getString("description"), amount, paid, amount.minus(paid), rs.getObject("issued_on", LocalDate.class),
                rs.getObject("due_date", LocalDate.class), rs.getString("cancel_reason"),
                rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant(), rs.getLong("version"), rs.getTimestamp("created_at").toInstant());
    }
}

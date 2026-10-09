package com.amanahconnect.billing;

import com.amanahconnect.billing.BillingDtos.PaymentView;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.page.PageResponse;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Searching a community's payment rows (payments, donations and reversals). Plain SQL, always scoped by community_id. */
@Component
public class PaymentQueries {

    public record PaymentFilter(LocalDate from, LocalDate to, PaymentMethod method, UUID memberId, UUID invoiceId, String kind) {}

    private static final Map<String, String> SORT_SQL = Map.of("receivedOn", "p.received_on", "amount", "p.amount", "createdAt", "p.created_at");

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<PaymentView> search(UUID communityId, PaymentFilter f, Pageable page) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", communityId);
        StringBuilder where = new StringBuilder(" WHERE p.community_id = :c");
        if (f.from() != null) { where.append(" AND p.received_on >= :from"); params.addValue("from", f.from()); }
        if (f.to() != null) { where.append(" AND p.received_on <= :to"); params.addValue("to", f.to()); }
        if (f.method() != null) { where.append(" AND p.method = :method"); params.addValue("method", f.method().name()); }
        if (f.memberId() != null) { where.append(" AND p.member_id = :member"); params.addValue("member", f.memberId()); }
        if (f.invoiceId() != null) { where.append(" AND p.invoice_id = :invoice"); params.addValue("invoice", f.invoiceId()); }
        if (f.kind() != null) {
            where.append(switch (f.kind()) {
                case "REVERSAL" -> " AND p.reversed_of IS NOT NULL";
                case "DONATION" -> " AND p.reversed_of IS NULL AND p.invoice_id IS NULL";
                default -> " AND p.reversed_of IS NULL AND p.invoice_id IS NOT NULL";
            });
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM payment_records p" + where, params, Long.class);
        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        var items = jdbc.query(
                "SELECT p.id, p.invoice_id, i.invoice_no, p.member_id, m.member_no, coalesce(m.full_name, p.donor_name) AS payer, p.amount, p.method, p.reference, p.received_on,"
                        + " p.reversed_of, p.reversal_reason, p.receipt_id, r.receipt_no, p.created_at,"
                        + " EXISTS (SELECT 1 FROM payment_records rv WHERE rv.community_id = :c AND rv.reversed_of = p.id) AS reversed"
                        + " FROM payment_records p LEFT JOIN invoices i ON i.id = p.invoice_id AND i.community_id = p.community_id"
                        + " LEFT JOIN members m ON m.id = p.member_id AND m.community_id = p.community_id LEFT JOIN receipts r ON r.id = p.receipt_id AND r.community_id = p.community_id"
                        + where + orderBy(page.getSort()) + " LIMIT :limit OFFSET :offset",
                params, (rs, row) -> {
                    UUID reversedOf = rs.getObject("reversed_of", UUID.class);
                    UUID invoiceId = rs.getObject("invoice_id", UUID.class);
                    return new PaymentView(
                            rs.getObject("id", UUID.class), reversedOf != null ? "REVERSAL" : invoiceId == null ? "DONATION" : "PAYMENT", invoiceId, rs.getString("invoice_no"),
                            rs.getObject("member_id", UUID.class), rs.getString("member_no"), rs.getString("payer"), Money.of(rs.getBigDecimal("amount")),
                            PaymentMethod.valueOf(rs.getString("method")), rs.getString("reference"), rs.getObject("received_on", LocalDate.class), reversedOf,
                            rs.getString("reversal_reason"), rs.getBoolean("reversed"), rs.getObject("receipt_id", UUID.class), rs.getString("receipt_no"), rs.getTimestamp("created_at").toInstant());
                });
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
        if (!any) out.append("p.created_at DESC");
        return out.append(", p.id").toString();
    }
}

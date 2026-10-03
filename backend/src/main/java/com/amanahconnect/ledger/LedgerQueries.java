package com.amanahconnect.ledger;

import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.file.ObjectStorage;
import com.amanahconnect.ledger.LedgerDtos.CategoryRef;
import com.amanahconnect.ledger.LedgerDtos.CategoryTotal;
import com.amanahconnect.ledger.LedgerDtos.EntryView;
import com.amanahconnect.ledger.LedgerDtos.MonthTotal;
import com.amanahconnect.ledger.LedgerDtos.SummaryView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Reading the ledger: entries and the summary. Plain SQL, always scoped by community_id. Totals are signed sums, so reversals net out. */
@Component
public class LedgerQueries {

    private static final Logger log = LoggerFactory.getLogger(LedgerQueries.class);

    public record EntryFilter(LocalDate from, LocalDate to, LedgerType type, UUID categoryId, LedgerSource source, String q) {}

    private static final Map<String, String> SORT_SQL = Map.of("entryDate", "e.entry_date", "amount", "e.amount", "createdAt", "e.created_at", "title", "lower(e.title)");

    private static final String SELECT =
            "SELECT e.id, e.type, c.id AS category_id, c.name AS category_name, e.amount, e.entry_date, e.title, e.notes, e.attachment_key, e.source, e.source_id,"
                    + " e.reversed_of, e.reversal_reason, e.created_at,"
                    + " EXISTS (SELECT 1 FROM ledger_entries rv WHERE rv.community_id = e.community_id AND rv.reversed_of = e.id) AS reversed"
                    + " FROM ledger_entries e JOIN ledger_categories c ON c.id = e.category_id AND c.community_id = e.community_id";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectStorage storage;

    public LedgerQueries(NamedParameterJdbcTemplate jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    public Optional<EntryView> find(UUID communityId, UUID id) {
        var rows = jdbc.query(SELECT + " WHERE e.community_id = :c AND e.id = :id", new MapSqlParameterSource("c", communityId).addValue("id", id), (rs, row) -> map(rs));
        return rows.stream().findFirst();
    }

    public PageResponse<EntryView> search(UUID communityId, EntryFilter f, Pageable page) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", communityId);
        StringBuilder where = new StringBuilder(" WHERE e.community_id = :c");
        if (f.from() != null) { where.append(" AND e.entry_date >= :from"); params.addValue("from", f.from()); }
        if (f.to() != null) { where.append(" AND e.entry_date <= :to"); params.addValue("to", f.to()); }
        if (f.type() != null) { where.append(" AND e.type = :type"); params.addValue("type", f.type().name()); }
        if (f.categoryId() != null) { where.append(" AND e.category_id = :cat"); params.addValue("cat", f.categoryId()); }
        if (f.source() != null) { where.append(" AND e.source = :source"); params.addValue("source", f.source().name()); }
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND lower(e.title) LIKE :q ESCAPE '\\'");
            params.addValue("q", "%" + f.q().trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM ledger_entries e" + where, params, Long.class);
        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        var items = jdbc.query(SELECT + where + orderBy(page.getSort()) + " LIMIT :limit OFFSET :offset", params, (rs, row) -> map(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page.getPageNumber(), page.getPageSize());
    }

    public SummaryView summary(UUID communityId, LocalDate from, LocalDate to, BigDecimal communityOpeningBalance) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", communityId).addValue("from", from, java.sql.Types.DATE).addValue("to", to, java.sql.Types.DATE);
        String range = " e.community_id = :c AND (CAST(:from AS date) IS NULL OR e.entry_date >= CAST(:from AS date)) AND (CAST(:to AS date) IS NULL OR e.entry_date <= CAST(:to AS date))";

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (Map<String, Object> row : jdbc.queryForList("SELECT e.type, coalesce(sum(e.amount), 0) AS total FROM ledger_entries e WHERE" + range + " GROUP BY e.type", params)) {
            BigDecimal total = (BigDecimal) row.get("total");
            if ("INCOME".equals(row.get("type"))) income = total; else expense = total;
        }
        List<CategoryTotal> byCategory = jdbc.query(
                "SELECT c.id, c.name, c.type, sum(e.amount) AS total FROM ledger_entries e JOIN ledger_categories c ON c.id = e.category_id AND c.community_id = e.community_id WHERE"
                        + range + " GROUP BY c.id, c.name, c.type ORDER BY c.type, sum(e.amount) DESC, c.name",
                params, (rs, row) -> new CategoryTotal(rs.getObject("id", UUID.class), rs.getString("name"), LedgerType.valueOf(rs.getString("type")), Money.of(rs.getBigDecimal("total"))));

        Map<String, BigDecimal[]> months = new LinkedHashMap<>();
        jdbc.query("SELECT to_char(e.entry_date, 'YYYY-MM') AS month, e.type, sum(e.amount) AS total FROM ledger_entries e WHERE" + range + " GROUP BY 1, 2 ORDER BY 1",
                params, rs -> {
                    BigDecimal[] pair = months.computeIfAbsent(rs.getString("month"), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                    pair["INCOME".equals(rs.getString("type")) ? 0 : 1] = rs.getBigDecimal("total");
                });
        List<MonthTotal> byMonth = new ArrayList<>();
        months.forEach((month, pair) -> byMonth.add(new MonthTotal(month, Money.of(pair[0]), Money.of(pair[1]), Money.of(pair[0].subtract(pair[1])))));

        BigDecimal before = BigDecimal.ZERO;
        if (from != null) {
            before = jdbc.queryForObject(
                    "SELECT coalesce(sum(CASE WHEN e.type = 'INCOME' THEN e.amount ELSE -e.amount END), 0) FROM ledger_entries e WHERE e.community_id = :c AND e.entry_date < :from",
                    new MapSqlParameterSource("c", communityId).addValue("from", from), BigDecimal.class);
        }
        Money opening = Money.of(communityOpeningBalance.add(before));
        Money net = Money.of(income.subtract(expense));
        return new SummaryView(from, to, opening, Money.of(income), Money.of(expense), net, opening.plus(net), byCategory, byMonth);
    }

    private EntryView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        LedgerSource source = LedgerSource.valueOf(rs.getString("source"));
        UUID reversedOf = rs.getObject("reversed_of", UUID.class);
        String attachmentKey = rs.getString("attachment_key");
        String url = null;
        if (attachmentKey != null) {
            try {
                url = storage.presignDownload(attachmentKey);
            } catch (RuntimeException e) {
                log.warn("Could not sign an attachment URL: {}", e.toString());
            }
        }
        return new EntryView(
                rs.getObject("id", UUID.class), LedgerType.valueOf(rs.getString("type")), new CategoryRef(rs.getObject("category_id", UUID.class), rs.getString("category_name")),
                Money.of(rs.getBigDecimal("amount")), rs.getObject("entry_date", LocalDate.class), rs.getString("title"), rs.getString("notes"), attachmentKey, url, source,
                rs.getObject("source_id", UUID.class), reversedOf, rs.getString("reversal_reason"), rs.getBoolean("reversed"),
                source == LedgerSource.PAYMENT || reversedOf != null || rs.getBoolean("reversed"), rs.getTimestamp("created_at").toInstant());
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
        if (!any) out.append("e.entry_date DESC, e.created_at DESC");
        return out.append(", e.id").toString();
    }
}

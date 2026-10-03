package com.amanahconnect.complaint;

import com.amanahconnect.common.Priority;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.complaint.ComplaintDtos.ComplaintCounts;
import com.amanahconnect.complaint.ComplaintDtos.ComplaintView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Searching and counting a community's complaints. Plain SQL, always scoped by community_id, sorting through a whitelist. */
@Component
public class ComplaintQueries {

    public record Filter(List<ComplaintStatus> statuses, Priority priority, String category, UUID memberId, UUID assignedTo, boolean unassigned, UUID mine,
                         Instant createdFrom, Instant createdBefore, boolean slaBreached, String q) {}

    private static final Map<String, String> SORT_SQL = Map.of(
            "createdAt", "c.created_at", "updatedAt", "c.updated_at", "status", "c.status", "subject", "lower(c.subject)",
            "priority", "CASE c.priority WHEN 'URGENT' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END");

    /** SQL twin of {@link ComplaintSla#breached}. */
    private static final String BREACHED =
            "(c.status IN ('OPEN', 'IN_PROGRESS') AND now() - c.created_at > make_interval(days => CASE c.priority WHEN 'URGENT' THEN 1 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 7 ELSE 14 END))";

    private static final String SELECT =
            "SELECT c.id, c.subject, c.description, c.status, c.priority, c.category, c.member_id, m.member_no, m.full_name AS member_name,"
                    + " c.assigned_to, au.full_name AS assigned_name, c.created_at, c.updated_at, c.resolved_at, c.closed_at,"
                    + " (SELECT count(*) FROM complaint_comments cc WHERE cc.complaint_id = c.id AND cc.community_id = c.community_id) AS comment_count"
                    + " FROM complaints c LEFT JOIN members m ON m.id = c.member_id AND m.community_id = c.community_id LEFT JOIN users au ON au.id = c.assigned_to";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ComplaintQueries(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<ComplaintView> find(UUID communityId, UUID id) {
        List<ComplaintView> rows = jdbc.query(SELECT + " WHERE c.community_id = :c AND c.id = :id", new MapSqlParameterSource("c", communityId).addValue("id", id), (rs, n) -> map(rs));
        return rows.stream().findFirst();
    }

    public PageResponse<ComplaintView> search(UUID communityId, Filter f, Pageable page) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", communityId);
        StringBuilder where = new StringBuilder(" WHERE c.community_id = :c");
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            where.append(" AND c.status IN (:statuses)");
            params.addValue("statuses", f.statuses().stream().map(Enum::name).toList());
        }
        if (f.priority() != null) { where.append(" AND c.priority = :priority"); params.addValue("priority", f.priority().name()); }
        if (f.category() != null && !f.category().isBlank()) { where.append(" AND lower(c.category) = :category"); params.addValue("category", f.category().trim().toLowerCase()); }
        if (f.memberId() != null) { where.append(" AND c.member_id = :member"); params.addValue("member", f.memberId()); }
        if (f.assignedTo() != null) { where.append(" AND c.assigned_to = :assigned"); params.addValue("assigned", f.assignedTo()); }
        if (f.unassigned()) where.append(" AND c.assigned_to IS NULL");
        if (f.mine() != null) { where.append(" AND c.assigned_to = :mine"); params.addValue("mine", f.mine()); }
        if (f.createdFrom() != null) { where.append(" AND c.created_at >= :from"); params.addValue("from", java.sql.Timestamp.from(f.createdFrom())); }
        if (f.createdBefore() != null) { where.append(" AND c.created_at < :before"); params.addValue("before", java.sql.Timestamp.from(f.createdBefore())); }
        if (f.slaBreached()) where.append(" AND ").append(BREACHED);
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (lower(c.subject) LIKE :q ESCAPE '\\' OR lower(c.description) LIKE :q ESCAPE '\\' OR lower(coalesce(m.full_name, '')) LIKE :q ESCAPE '\\' OR lower(coalesce(m.member_no, '')) LIKE :q ESCAPE '\\')");
            params.addValue("q", "%" + f.q().trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM complaints c LEFT JOIN members m ON m.id = c.member_id AND m.community_id = c.community_id" + where, params, Long.class);
        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        List<ComplaintView> items = jdbc.query(SELECT + where + orderBy(page.getSort()) + " LIMIT :limit OFFSET :offset", params, (rs, n) -> map(rs));
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
        if (!any) out.append("c.created_at DESC");
        return out.append(", c.id").toString();
    }

    public ComplaintCounts counts(UUID communityId, UUID me) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", communityId).addValue("me", me);
        long[] n = new long[9];
        jdbc.query(
                "SELECT count(*) AS total, count(*) FILTER (WHERE c.status = 'OPEN') AS open, count(*) FILTER (WHERE c.status = 'IN_PROGRESS') AS in_progress,"
                        + " count(*) FILTER (WHERE c.status = 'RESOLVED') AS resolved, count(*) FILTER (WHERE c.status = 'CLOSED') AS closed,"
                        + " count(*) FILTER (WHERE " + BREACHED + ") AS breached,"
                        + " count(*) FILTER (WHERE c.status IN ('OPEN', 'IN_PROGRESS') AND c.priority = 'URGENT') AS urgent,"
                        + " count(*) FILTER (WHERE c.status IN ('OPEN', 'IN_PROGRESS') AND c.assigned_to IS NULL) AS unassigned,"
                        + " count(*) FILTER (WHERE c.status IN ('OPEN', 'IN_PROGRESS') AND c.assigned_to = :me) AS mine"
                        + " FROM complaints c WHERE c.community_id = :c",
                params, (ResultSet rs) -> {
                    n[0] = rs.getLong("total"); n[1] = rs.getLong("open"); n[2] = rs.getLong("in_progress"); n[3] = rs.getLong("resolved"); n[4] = rs.getLong("closed");
                    n[5] = rs.getLong("breached"); n[6] = rs.getLong("urgent"); n[7] = rs.getLong("unassigned"); n[8] = rs.getLong("mine");
                });
        Map<String, Long> byCategory = new LinkedHashMap<>();
        jdbc.query("SELECT coalesce(nullif(btrim(c.category), ''), 'Uncategorised') AS category, count(*) AS n FROM complaints c WHERE c.community_id = :c AND c.status IN ('OPEN', 'IN_PROGRESS')"
                + " GROUP BY 1 ORDER BY n DESC, 1", params, (ResultSet rs) -> { byCategory.put(rs.getString("category"), rs.getLong("n")); });
        Map<String, Long> byPriority = new LinkedHashMap<>();
        for (Priority p : Priority.values()) byPriority.put(p.name(), 0L);
        jdbc.query("SELECT c.priority, count(*) AS n FROM complaints c WHERE c.community_id = :c AND c.status IN ('OPEN', 'IN_PROGRESS') GROUP BY 1", params,
                (ResultSet rs) -> { byPriority.put(rs.getString("priority"), rs.getLong("n")); });
        return new ComplaintCounts(n[0], n[1], n[2], n[3], n[4], n[1] + n[2], n[5], n[6], n[7], n[8], byCategory, byPriority);
    }

    private ComplaintView map(ResultSet rs) throws SQLException {
        Instant now = clock.instant();
        ComplaintStatus status = ComplaintStatus.valueOf(rs.getString("status"));
        Priority priority = Priority.valueOf(rs.getString("priority"));
        Instant created = rs.getTimestamp("created_at").toInstant();
        Instant resolved = instant(rs, "resolved_at");
        Instant closed = instant(rs, "closed_at");
        return new ComplaintView(
                rs.getObject("id", UUID.class), rs.getString("subject"), rs.getString("description"), status, priority, rs.getString("category"),
                rs.getObject("member_id", UUID.class), rs.getString("member_no"), rs.getString("member_name"),
                rs.getObject("assigned_to", UUID.class), rs.getString("assigned_name"), created, rs.getTimestamp("updated_at").toInstant(), resolved, closed,
                ComplaintSla.ageDays(created, resolved, closed, now), ComplaintSla.targetDays(priority), ComplaintSla.breached(status, priority, created, now),
                rs.getLong("comment_count"), null);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}

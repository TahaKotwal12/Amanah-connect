package com.amanahconnect.support;

import com.amanahconnect.common.Priority;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.file.ObjectStorage;
import com.amanahconnect.support.SupportDtos.AttachmentView;
import com.amanahconnect.support.SupportDtos.MessageView;
import com.amanahconnect.support.SupportDtos.PlatformCounts;
import com.amanahconnect.support.SupportDtos.Summary;
import com.amanahconnect.support.SupportDtos.ThreadSummary;
import com.amanahconnect.support.SupportDtos.ThreadView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reading the helpdesk. Plain SQL. A community viewer's every query carries its community id; only the platform viewer
 * reads across communities, and it is a different code path ({@link SupportViewer#isPlatform()}), not an optional filter.
 */
@Component
public class SupportQueries {

    public record Filter(List<ThreadStatus> statuses, Priority priority, UUID communityId, UUID assignedTo, boolean unassigned, UUID mine, boolean unreadOnly, String q) {}

    private static final java.util.Map<String, String> SORT_SQL = java.util.Map.of(
            "lastMessageAt", "t.last_message_at", "createdAt", "t.created_at", "status", "t.status", "subject", "lower(t.subject)", "community", "lower(c.name)",
            "priority", "CASE t.priority WHEN 'URGENT' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectStorage storage;

    public SupportQueries(NamedParameterJdbcTemplate jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    private static String select(SupportViewer viewer) {
        // unread = what the OTHER side wrote and this side has not read
        return "SELECT t.id, t.community_id, c.name AS community_name, t.subject, t.status, t.priority, t.assigned_to, au.full_name AS assigned_name,"
                + " t.created_by, cu.full_name AS created_name, t.created_at, t.last_message_at, t.closed_at, t.message_seq,"
                + " (SELECT count(*) FROM support_messages m WHERE m.thread_id = t.id AND m.community_id = t.community_id AND m.sender_side = '" + viewer.side().other().name() + "' AND m.read_at IS NULL) AS unread,"
                + " (SELECT left(m.body, 120) FROM support_messages m WHERE m.thread_id = t.id AND m.community_id = t.community_id ORDER BY m.seq DESC LIMIT 1) AS preview"
                + " FROM support_threads t JOIN communities c ON c.id = t.community_id LEFT JOIN users au ON au.id = t.assigned_to JOIN users cu ON cu.id = t.created_by";
    }

    private static void scope(SupportViewer viewer, StringBuilder where, MapSqlParameterSource params) {
        if (viewer.isPlatform()) {
            where.append(" WHERE true");
        } else {
            where.append(" WHERE t.community_id = :viewerCommunity");
            params.addValue("viewerCommunity", viewer.communityId());
        }
    }

    public Optional<ThreadView> find(SupportViewer viewer, UUID id) {
        StringBuilder where = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        scope(viewer, where, params);
        where.append(" AND t.id = :id");
        return jdbc.query(select(viewer) + where, params, (rs, n) -> thread(rs)).stream().findFirst();
    }

    public PageResponse<ThreadView> search(SupportViewer viewer, Filter f, Pageable page) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder();
        scope(viewer, where, params);
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            where.append(" AND t.status IN (:statuses)");
            params.addValue("statuses", f.statuses().stream().map(Enum::name).toList());
        }
        if (f.priority() != null) { where.append(" AND t.priority = :priority"); params.addValue("priority", f.priority().name()); }
        if (viewer.isPlatform() && f.communityId() != null) { where.append(" AND t.community_id = :filterCommunity"); params.addValue("filterCommunity", f.communityId()); }
        if (f.assignedTo() != null) { where.append(" AND t.assigned_to = :assigned"); params.addValue("assigned", f.assignedTo()); }
        if (f.unassigned()) where.append(" AND t.assigned_to IS NULL");
        if (f.mine() != null) { where.append(" AND t.assigned_to = :mine"); params.addValue("mine", f.mine()); }
        if (f.unreadOnly()) {
            where.append(" AND EXISTS (SELECT 1 FROM support_messages m WHERE m.thread_id = t.id AND m.community_id = t.community_id AND m.sender_side = '")
                    .append(viewer.side().other().name()).append("' AND m.read_at IS NULL)");
        }
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (lower(t.subject) LIKE :q ESCAPE '\\'").append(viewer.isPlatform() ? " OR lower(c.name) LIKE :q ESCAPE '\\'" : "").append(")");
            params.addValue("q", "%" + f.q().trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        String from = " FROM support_threads t JOIN communities c ON c.id = t.community_id";
        Long total = jdbc.queryForObject("SELECT count(*)" + from + where, params, Long.class);
        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        List<ThreadView> items = jdbc.query(select(viewer) + where + orderBy(page.getSort()) + " LIMIT :limit OFFSET :offset", params, (rs, n) -> thread(rs));
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
        if (!any) out.append("t.last_message_at DESC");
        return out.append(", t.id").toString();
    }

    /** Messages with seq greater than {@code after}, oldest first; one extra row tells whether there is more. */
    public List<MessageView> messagesAfter(UUID communityId, UUID threadId, long after, int limit) {
        return jdbc.query(
                "SELECT m.id, m.seq, m.sender_side, m.sender_user_id, u.full_name, m.body, m.attachment_key, m.attachment_name, m.attachment_content_type, m.attachment_size, m.created_at, m.read_at"
                        + " FROM support_messages m JOIN users u ON u.id = m.sender_user_id WHERE m.community_id = :c AND m.thread_id = :t AND m.seq > :after ORDER BY m.seq LIMIT :limit",
                new MapSqlParameterSource("c", communityId).addValue("t", threadId).addValue("after", after).addValue("limit", limit),
                (rs, n) -> message(rs));
    }

    public Summary summary(SupportViewer viewer) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder();
        scope(viewer, where, params);
        List<ThreadSummary> threads = jdbc.query(
                "SELECT t.id, t.message_seq, t.status, t.last_message_at,"
                        + " (SELECT count(*) FROM support_messages m WHERE m.thread_id = t.id AND m.community_id = t.community_id AND m.sender_side = '" + viewer.side().other().name() + "' AND m.read_at IS NULL) AS unread"
                        + " FROM support_threads t" + where + " AND t.status <> 'CLOSED' ORDER BY t.last_message_at DESC LIMIT 200",
                params, (rs, n) -> new ThreadSummary(rs.getObject("id", UUID.class), rs.getLong("message_seq"), ThreadStatus.valueOf(rs.getString("status")), rs.getLong("unread"), rs.getTimestamp("last_message_at").toInstant()));
        long unreadMessages = jdbc.queryForObject(
                "SELECT count(*) FROM support_messages m JOIN support_threads t ON t.id = m.thread_id AND t.community_id = m.community_id" + where
                        + " AND m.sender_side = '" + viewer.side().other().name() + "' AND m.read_at IS NULL", params, Long.class);
        long unreadThreads = threads.stream().filter(t -> t.unreadCount() > 0).count();
        long active = threads.stream().filter(t -> t.status() != ThreadStatus.RESOLVED).count();
        return new Summary(unreadMessages, unreadThreads, active, threads);
    }

    public PlatformCounts platformCounts() {
        long[] n = new long[8];
        jdbc.query(
                "SELECT count(*) AS total, count(*) FILTER (WHERE t.status = 'OPEN') AS open, count(*) FILTER (WHERE t.status = 'WAITING') AS waiting,"
                        + " count(*) FILTER (WHERE t.status = 'RESOLVED') AS resolved, count(*) FILTER (WHERE t.status = 'CLOSED') AS closed,"
                        + " count(*) FILTER (WHERE t.status IN ('OPEN', 'WAITING') AND t.assigned_to IS NULL) AS unassigned,"
                        + " count(*) FILTER (WHERE t.status IN ('OPEN', 'WAITING') AND t.priority = 'URGENT') AS urgent,"
                        + " count(*) FILTER (WHERE EXISTS (SELECT 1 FROM support_messages m WHERE m.thread_id = t.id AND m.community_id = t.community_id AND m.sender_side = 'COMMUNITY' AND m.read_at IS NULL)) AS unread_threads"
                        + " FROM support_threads t",
                new MapSqlParameterSource(), (ResultSet rs) -> {
                    n[0] = rs.getLong("total"); n[1] = rs.getLong("open"); n[2] = rs.getLong("waiting"); n[3] = rs.getLong("resolved"); n[4] = rs.getLong("closed");
                    n[5] = rs.getLong("unassigned"); n[6] = rs.getLong("urgent"); n[7] = rs.getLong("unread_threads");
                });
        return new PlatformCounts(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7]);
    }

    private static ThreadView thread(ResultSet rs) throws SQLException {
        return new ThreadView(
                rs.getObject("id", UUID.class), rs.getObject("community_id", UUID.class), rs.getString("community_name"), rs.getString("subject"),
                ThreadStatus.valueOf(rs.getString("status")), Priority.valueOf(rs.getString("priority")), rs.getObject("assigned_to", UUID.class), rs.getString("assigned_name"),
                rs.getObject("created_by", UUID.class), rs.getString("created_name"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_message_at").toInstant(),
                instant(rs, "closed_at"), rs.getLong("message_seq"), rs.getLong("unread"), rs.getString("preview"));
    }

    private MessageView message(ResultSet rs) throws SQLException {
        AttachmentView attachment = null;
        String key = rs.getString("attachment_key");
        if (key != null) {
            attachment = new AttachmentView(rs.getString("attachment_name"), rs.getString("attachment_content_type"), rs.getLong("attachment_size"), downloadUrl(key));
        }
        return new MessageView(rs.getObject("id", UUID.class), rs.getLong("seq"), SupportSide.valueOf(rs.getString("sender_side")), rs.getObject("sender_user_id", UUID.class),
                rs.getString("full_name"), rs.getString("body"), attachment, rs.getTimestamp("created_at").toInstant(), instant(rs, "read_at"));
    }

    /** A short-lived link, or null when storage is not reachable (the message still shows; only the file link is missing). */
    private String downloadUrl(String key) {
        try {
            return storage.presignDownload(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}

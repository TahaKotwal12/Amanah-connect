package com.amanahconnect.announcement;

import com.amanahconnect.announcement.AnnouncementDtos.NotificationSummary;
import com.amanahconnect.announcement.AnnouncementDtos.NotificationView;
import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.page.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The community admin's notification area: the platform announcements and offers addressed to their community (sent, not expired,
 * and sent after the community existed), with a read state per admin, and the banners still to show. Only the caller's community
 * and the caller's own read marks are ever consulted.
 */
@Service
@Transactional
public class NotificationService {

    private static final String VISIBLE =
            " FROM announcements a JOIN communities c ON c.id = :cid LEFT JOIN announcement_reads r ON r.announcement_id = a.id AND r.user_id = :uid"
                    + " WHERE a.community_id IS NULL AND a.status = 'SENT' AND a.sent_at >= c.created_at AND (a.expires_at IS NULL OR a.expires_at > now())"
                    + " AND (NOT jsonb_exists(a.audience_filter, 'communityIds') OR a.audience_filter -> 'communityIds' @> to_jsonb(CAST(:cid AS text)))";

    private static final String COLUMNS = "SELECT a.id, a.title, a.body, a.kind, a.banner, a.sent_at, a.expires_at, (r.id IS NOT NULL) AS read";

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public NotificationService(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> list(UUID communityId, boolean unreadOnly, Pageable page) {
        MapSqlParameterSource params = params(communityId);
        String filter = unreadOnly ? " AND r.id IS NULL" : "";
        Long total = jdbc.queryForObject("SELECT count(*)" + VISIBLE + filter, params, Long.class);
        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        List<NotificationView> items = jdbc.query(COLUMNS + VISIBLE + filter + " ORDER BY a.sent_at DESC, a.id LIMIT :limit OFFSET :offset", params, (rs, n) -> view(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page.getPageNumber(), page.getPageSize());
    }

    @Transactional(readOnly = true)
    public NotificationSummary summary(UUID communityId) {
        MapSqlParameterSource params = params(communityId);
        Long unread = jdbc.queryForObject("SELECT count(*)" + VISIBLE + " AND r.id IS NULL", params, Long.class);
        List<NotificationView> banners = jdbc.query(COLUMNS + VISIBLE + " AND a.banner AND r.id IS NULL ORDER BY a.sent_at DESC, a.id LIMIT 5", params, (rs, n) -> view(rs));
        return new NotificationSummary(unread == null ? 0 : unread, banners);
    }

    /** Marks one notification read for the caller (dismisses its banner). One that is not addressed to this community is a 404. */
    public NotificationSummary markRead(UUID communityId, UUID announcementId) {
        MapSqlParameterSource params = params(communityId).addValue("id", announcementId);
        Integer visible = jdbc.queryForObject("SELECT count(*)" + VISIBLE + " AND a.id = :id", params, Integer.class);
        if (visible == null || visible == 0) throw new NotFoundException();
        int inserted = jdbc.update("INSERT INTO announcement_reads (announcement_id, user_id) VALUES (:id, :uid) ON CONFLICT (announcement_id, user_id) DO NOTHING", params);
        if (inserted > 0) audit.record("NOTIFICATION_READ", "Announcement", announcementId, null, Map.of("announcementId", announcementId.toString()));
        return summary(communityId);
    }

    public NotificationSummary markAllRead(UUID communityId) {
        MapSqlParameterSource params = params(communityId);
        int inserted = jdbc.update("INSERT INTO announcement_reads (announcement_id, user_id) SELECT a.id, :uid" + VISIBLE + " AND r.id IS NULL ON CONFLICT (announcement_id, user_id) DO NOTHING", params);
        if (inserted > 0) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("marked", inserted);
            audit.record("NOTIFICATIONS_READ_ALL", "Announcement", null, null, after);
        }
        return summary(communityId);
    }

    private static MapSqlParameterSource params(UUID communityId) {
        return new MapSqlParameterSource("cid", communityId).addValue("uid", AuditService.currentActorId());
    }

    private static NotificationView view(ResultSet rs) throws SQLException {
        String html = rs.getString("body");
        var expires = rs.getTimestamp("expires_at");
        return new NotificationView(rs.getObject("id", UUID.class), rs.getString("title"), html, RichText.text(html), AnnouncementKind.valueOf(rs.getString("kind")), rs.getBoolean("banner"),
                rs.getTimestamp("sent_at").toInstant(), expires == null ? null : expires.toInstant(), rs.getBoolean("read"));
    }
}

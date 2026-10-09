package com.amanahconnect.audit;

import com.amanahconnect.audit.AuditDtos.AdminEntry;
import com.amanahconnect.audit.AuditDtos.CommunityEntry;
import com.amanahconnect.audit.AuditDtos.Filter;
import com.amanahconnect.audit.AuditDtos.Page;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading the audit trail. Newest first, by keyset: a page is "the rows older than (created_at, id) of the last row you saw", which stays
 * fast however deep you go and never skips or repeats a row when new ones arrive (OFFSET would). Every filter combines with AND and the
 * indexes end in (created_at DESC, id DESC) to serve it. The community variant always carries the caller's community id.
 */
@Component
public class AuditQueries {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    public static final int EXPORT_LIMIT = 100_000;

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private static final String ADMIN_SELECT =
            "SELECT a.id, a.created_at, a.action, a.actor_user_id, u.full_name AS actor_name, u.role AS actor_role, a.community_id, c.name AS community_name,"
                    + " a.entity_type, a.entity_id, a.before::text AS before_json, a.after::text AS after_json, a.ip, a.user_agent, a.request_id, c.currency"
                    + " FROM audit_logs a LEFT JOIN users u ON u.id = a.actor_user_id LEFT JOIN communities c ON c.id = a.community_id";

    private final NamedParameterJdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate streaming;
    private final JsonMapper json;

    public AuditQueries(NamedParameterJdbcTemplate jdbc, DataSource dataSource, JsonMapper json) {
        this.jdbc = jdbc;
        JdbcTemplate cursor = new JdbcTemplate(dataSource);
        cursor.setFetchSize(1000); // a real database cursor inside the caller's transaction, so 100k rows never sit in memory
        this.streaming = new NamedParameterJdbcTemplate(cursor);
        this.json = json;
    }

    // ---- pages ------------------------------------------------------------------------------------------------------------

    public Page<AdminEntry> adminPage(Filter filter, String cursor, int limit) {
        return page(filter, null, cursor, limit, this::adminEntry);
    }

    public Page<CommunityEntry> communityPage(UUID communityId, Filter filter, String cursor, int limit) {
        Filter scoped = new Filter(filter.actor(), communityId, filter.action(), filter.actionPrefix(), filter.entityType(), filter.entityId(), filter.from(), filter.toExclusive());
        return page(scoped, communityId, cursor, limit, this::communityEntry);
    }

    private <T> Page<T> page(Filter filter, UUID forcedCommunity, String cursor, int limit, RowMapperWithKey<T> mapper) {
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(where(filter, params));
        if (cursor != null && !cursor.isBlank()) {
            Cursor c = Cursor.decode(cursor);
            where.append(where.length() == 0 ? " WHERE " : " AND ").append("(a.created_at, a.id) < (:cursorAt, :cursorId)");
            params.addValue("cursorAt", Timestamp.from(c.at())).addValue("cursorId", c.id());
        }
        params.addValue("limit", size + 1);
        List<Keyed<T>> rows = jdbc.query(ADMIN_SELECT + where + " ORDER BY a.created_at DESC, a.id DESC LIMIT :limit", params, (rs, n) -> mapper.map(rs));
        boolean more = rows.size() > size;
        List<Keyed<T>> kept = more ? rows.subList(0, size) : rows;
        String next = more ? new Cursor(kept.get(kept.size() - 1).at(), kept.get(kept.size() - 1).id()).encode() : null;
        return new Page<>(kept.stream().map(Keyed::value).toList(), next, more);
    }

    // ---- export -----------------------------------------------------------------------------------------------------------

    /** How many rows match, counting no further than {@code cap + 1}: enough to know whether an export would be cut off. */
    public int countUpTo(Filter filter, int cap) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params);
        params.addValue("cap", cap + 1);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM audit_logs a" + where + " ORDER BY a.created_at DESC, a.id DESC LIMIT :cap) t", params, Integer.class);
        return n == null ? 0 : n;
    }

    /** Streams up to {@code limit} matching rows, newest first, one at a time. Must run inside a (read-only) transaction for the cursor to work. */
    public void stream(Filter filter, int limit, java.util.function.Consumer<AdminEntry> sink) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params);
        params.addValue("limit", limit);
        RowCallbackHandler handler = rs -> sink.accept(adminEntry(rs).value());
        streaming.query(ADMIN_SELECT + where + " ORDER BY a.created_at DESC, a.id DESC LIMIT :limit", params, handler);
    }

    // ---- the dashboard's recent activity ------------------------------------------------------------------------------------

    /** The newest {@code limit} entries of a community that are worth showing, one query. */
    public List<CommunityEntry> recentForCommunity(UUID communityId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", communityId).addValue("noise", List.copyOf(AuditHumanizer.NOT_DASHBOARD_WORTHY)).addValue("limit", limit);
        return jdbc.query(ADMIN_SELECT + " WHERE a.community_id = :c AND a.action NOT IN (:noise) ORDER BY a.created_at DESC, a.id DESC LIMIT :limit", params,
                (rs, n) -> communityEntry(rs).value());
    }

    // ---- building -----------------------------------------------------------------------------------------------------------

    private String where(Filter f, MapSqlParameterSource params) {
        List<String> parts = new ArrayList<>();
        if (f.actor() != null) { parts.add("a.actor_user_id = :actor"); params.addValue("actor", f.actor()); }
        if (f.communityId() != null) { parts.add("a.community_id = :community"); params.addValue("community", f.communityId()); }
        if (f.action() != null && !f.action().isBlank()) { parts.add("a.action = :action"); params.addValue("action", f.action().trim()); }
        if (f.actionPrefix() != null && !f.actionPrefix().isBlank()) {
            parts.add("a.action LIKE :prefix ESCAPE '\\'");
            params.addValue("prefix", f.actionPrefix().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.entityType() != null && !f.entityType().isBlank()) { parts.add("a.entity_type = :entityType"); params.addValue("entityType", f.entityType().trim()); }
        if (f.entityId() != null) { parts.add("a.entity_id = :entityId"); params.addValue("entityId", f.entityId()); }
        if (f.from() != null) { parts.add("a.created_at >= :from"); params.addValue("from", Timestamp.from(f.from())); }
        if (f.toExclusive() != null) { parts.add("a.created_at < :to"); params.addValue("to", Timestamp.from(f.toExclusive())); }
        return parts.isEmpty() ? "" : " WHERE " + String.join(" AND ", parts);
    }

    private interface RowMapperWithKey<T> {
        Keyed<T> map(ResultSet rs) throws SQLException;
    }

    private record Keyed<T>(Instant at, UUID id, T value) {}

    private Keyed<AdminEntry> adminEntry(ResultSet rs) throws SQLException {
        Instant at = rs.getTimestamp("created_at").toInstant();
        UUID id = rs.getObject("id", UUID.class);
        Map<String, Object> before = parse(rs.getString("before_json"));
        Map<String, Object> after = parse(rs.getString("after_json"));
        String action = rs.getString("action");
        return new Keyed<>(at, id, new AdminEntry(id, at, action, AuditHumanizer.describe(action, before, after, rs.getString("currency")), rs.getObject("actor_user_id", UUID.class), rs.getString("actor_name"),
                rs.getString("actor_role"), rs.getObject("community_id", UUID.class), rs.getString("community_name"), rs.getString("entity_type"), rs.getObject("entity_id", UUID.class), before, after,
                rs.getString("ip"), rs.getString("user_agent"), rs.getString("request_id")));
    }

    private Keyed<CommunityEntry> communityEntry(ResultSet rs) throws SQLException {
        Instant at = rs.getTimestamp("created_at").toInstant();
        UUID id = rs.getObject("id", UUID.class);
        Map<String, Object> before = parse(rs.getString("before_json"));
        Map<String, Object> after = parse(rs.getString("after_json"));
        String action = rs.getString("action");
        boolean platform = "SUPER_ADMIN".equals(rs.getString("actor_role"));
        String actor = platform ? "Amanah Connect staff" : rs.getString("actor_name");
        return new Keyed<>(at, id, new CommunityEntry(id, at, action, AuditHumanizer.describe(action, before, after, rs.getString("currency")), actor == null ? "System" : actor, platform,
                rs.getString("entity_type"), rs.getObject("entity_id", UUID.class), before, after));
    }

    private Map<String, Object> parse(String text) {
        if (text == null) return null;
        try {
            return json.readValue(text, MAP);
        } catch (RuntimeException e) {
            return Map.of("unreadable", true);
        }
    }

    /** A position in the trail: the (time, id) of the last row of the previous page. Opaque to clients. */
    record Cursor(Instant at, UUID id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((at.getEpochSecond() + "." + at.getNano() + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String value) {
            try {
                String[] halves = new String(Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8).split("\\|", 2);
                String[] time = halves[0].split("\\.", 2);
                return new Cursor(Instant.ofEpochSecond(Long.parseLong(time[0]), Long.parseLong(time[1])), UUID.fromString(halves[1]));
            } catch (RuntimeException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("cursor: not a cursor this server issued"));
            }
        }
    }

}

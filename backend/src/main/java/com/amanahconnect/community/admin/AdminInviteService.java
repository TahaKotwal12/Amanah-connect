package com.amanahconnect.community.admin;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.auth.AuthTokenPurpose;
import com.amanahconnect.auth.AuthTokenRepository;
import com.amanahconnect.auth.InvitationService;
import com.amanahconnect.auth.User;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.auth.UserStatus;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.page.PageResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The super admin's view of administrator invitations. One row per invited user, from their most
 * recent invitation: PENDING (usable), EXPIRED (not used in time), ACCEPTED (the user is active) or
 * REVOKED (withdrawn, or the account was disabled). Resending issues a new link and cancels older ones.
 */
@Service
public class AdminInviteService {

    public record InviteRow(
            UUID userId,
            String email,
            String fullName,
            UUID communityId,
            String communityName,
            String status,
            Instant invitedAt,
            Instant expiresAt,
            Instant acceptedAt) {}

    private static final String STATUS_SQL =
            "CASE WHEN u.status = 'ACTIVE' THEN 'ACCEPTED'"
                    + " WHEN u.status = 'DISABLED' THEN 'REVOKED'"
                    + " WHEN l.used_at IS NOT NULL THEN 'REVOKED'"
                    + " WHEN l.expires_at < :now THEN 'EXPIRED'"
                    + " ELSE 'PENDING' END";

    private static final String BASE =
            "SELECT u.id AS user_id, u.email, u.full_name, c.id AS community_id, c.name AS community_name,"
                    + " l.created_at AS invited_at, l.expires_at, "
                    + "CASE WHEN u.status = 'ACTIVE' THEN l.used_at END AS accepted_at, "
                    + STATUS_SQL + " AS invite_status"
                    + " FROM (SELECT DISTINCT ON (t.user_id) t.user_id, t.created_at, t.expires_at, t.used_at"
                    + "         FROM auth_tokens t WHERE t.purpose = 'INVITATION' ORDER BY t.user_id, t.created_at DESC) l"
                    + " JOIN users u ON u.id = l.user_id"
                    + " LEFT JOIN community_users cu ON cu.user_id = u.id"
                    + " LEFT JOIN communities c ON c.id = cu.community_id";

    private static final Map<String, String> SORT_SQL =
            Map.of(
                    "invitedAt", "t.invited_at",
                    "expiresAt", "t.expires_at",
                    "email", "lower(t.email)",
                    "communityName", "lower(t.community_name)",
                    "status", "t.invite_status");

    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;
    private final AuthTokenRepository authTokens;
    private final InvitationService invitations;
    private final AuditService audit;
    private final Clock clock;

    public AdminInviteService(
            NamedParameterJdbcTemplate jdbc,
            UserRepository users,
            AuthTokenRepository authTokens,
            InvitationService invitations,
            AuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.authTokens = authTokens;
        this.invitations = invitations;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<InviteRow> list(String status, String q, PageRequest page) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("now", Timestamp.from(clock.instant()));
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null) {
            where.append(" AND t.invite_status = :status");
            params.addValue("status", status);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (lower(t.email) LIKE :q OR lower(t.full_name) LIKE :q OR lower(coalesce(t.community_name, '')) LIKE :q)");
            params.addValue("q", "%" + q.trim().toLowerCase().replace("%", "").replace("_", "") + "%");
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + BASE + ") t" + where, params, Long.class);
        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        List<InviteRow> items = jdbc.query(
                "SELECT t.* FROM (" + BASE + ") t" + where + orderBy(page.getSort()) + " LIMIT :limit OFFSET :offset",
                params,
                (rs, row) -> new InviteRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getObject("community_id", UUID.class),
                        rs.getString("community_name"),
                        rs.getString("invite_status"),
                        rs.getTimestamp("invited_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("accepted_at") == null ? null : rs.getTimestamp("accepted_at").toInstant()));
        return new PageResponse<>(items, total == null ? 0 : total, page.getPageNumber(), page.getPageSize());
    }

    /** Issues a new link and cancels the old ones. Only for someone who has not accepted yet. */
    @Transactional
    public void resend(UUID userId) {
        User user = pendingUser(userId);
        invitations.invite(user, AuditService.currentActorId());
    }

    /** Withdraws the usable link. The account stays INVITED, so the invitation can be sent again. */
    @Transactional
    public void revoke(UUID userId) {
        User user = pendingUser(userId);
        UUID communityId = jdbc.query(
                        "SELECT community_id FROM community_users WHERE user_id = :id LIMIT 1",
                        new MapSqlParameterSource("id", userId),
                        (rs, i) -> rs.getObject("community_id", UUID.class))
                .stream()
                .findFirst()
                .orElse(null);
        String email = user.getEmail();
        int cancelled = authTokens.invalidateUnused(userId, AuthTokenPurpose.INVITATION, clock.instant());
        if (cancelled == 0) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This invitation is not active, so there is nothing to revoke.");
        }
        audit.recordForCommunity("INVITATION_REVOKED", communityId, "User", userId, null, Map.of("email", email));
    }

    private User pendingUser(UUID userId) {
        User user = users.findById(userId).orElseThrow(NotFoundException::new);
        Long invitations = jdbc.queryForObject(
                "SELECT count(*) FROM auth_tokens WHERE user_id = :id AND purpose = 'INVITATION'",
                new MapSqlParameterSource("id", userId),
                Long.class);
        if (invitations == null || invitations == 0) {
            throw new NotFoundException();
        }
        if (user.getStatus() != UserStatus.INVITED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This person has already accepted, or the account is disabled.");
        }
        return user;
    }

    private static String orderBy(Sort sort) {
        List<String> parts = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = order.getProperty().equals("id") ? "t.user_id" : SORT_SQL.get(order.getProperty());
            if (column == null) {
                throw new IllegalArgumentException("Sort property is not whitelisted: " + order.getProperty());
            }
            parts.add(column + (order.isDescending() ? " DESC" : " ASC"));
        }
        return parts.isEmpty() ? " ORDER BY t.invited_at DESC" : " ORDER BY " + String.join(", ", parts);
    }
}

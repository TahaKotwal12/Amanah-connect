package com.amanahconnect.announcement;

import com.amanahconnect.announcement.AnnouncementDtos.DeliveryView;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.member.MemberStatus;
import com.amanahconnect.notification.EmailOutbox;
import com.amanahconnect.notification.EmailOutboxRepository;
import com.amanahconnect.plan.PlanLimitService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns an announcement into outbox emails. It runs inside the caller's transaction, with the announcement row already locked
 * by the caller, so an announcement is dispatched exactly once however many people click Send or however many instances run the
 * schedule. A member is emailed only if they have an address AND agreed to email; the community's monthly email quota is checked
 * once and counted down, and the rest are recorded as skipped, not silently dropped.
 */
@Component
public class AnnouncementDispatcher {

    public static final String MEMBER_TEMPLATE = "member-announcement";
    public static final String ADMIN_TEMPLATE = "platform-announcement";

    private final MemberRepository members;
    private final CommunityRepository communities;
    private final PlanLimitService planLimits;
    private final EmailOutboxRepository outbox;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AnnouncementDispatcher(MemberRepository members, CommunityRepository communities, PlanLimitService planLimits, EmailOutboxRepository outbox, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.members = members;
        this.communities = communities;
        this.planLimits = planLimits;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Active members the announcement is for, in member number order (so a quota cut-off is predictable). */
    public List<Member> audience(UUID communityId, Announcement a) {
        List<Member> found = switch (a.getAudience()) {
            case ALL_ACTIVE -> members.findByCommunityIdAndStatusAndDeletedAtIsNull(communityId, MemberStatus.ACTIVE);
            case GROUP -> members.findGroupMembersByCommunityId(communityId, MemberStatus.ACTIVE, String.valueOf(a.getAudienceFilter().get("group")).toLowerCase());
            case SELECTED -> members.findByCommunityIdAndIdInAndDeletedAtIsNull(communityId, memberIds(a)).stream().filter(m -> m.getStatus() == MemberStatus.ACTIVE).toList();
            case COMMUNITY_ADMINS -> List.of();
        };
        List<Member> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparing(Member::getMemberNo).thenComparing(Member::getId));
        return sorted;
    }

    public static List<UUID> memberIds(Announcement a) {
        Object raw = a.getAudienceFilter().get("memberIds");
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(o -> UUID.fromString(String.valueOf(o))).toList();
    }

    /** Queues the member emails (if send_email), records the counts and marks the announcement SENT. */
    public DeliveryView dispatchToMembers(Announcement a) {
        UUID communityId = a.getCommunityId();
        Community community = communities.findById(communityId).orElseThrow(NotFoundException::new);
        lockEmailQuota(communityId);
        List<Member> audience = audience(communityId, a);
        int queued = 0, noEmail = 0, noConsent = 0, quota = 0;
        if (a.isSendEmail()) {
            long left = planLimits.emailQuotaRemaining(communityId);
            String text = RichText.text(a.getBody());
            for (Member m : audience) {
                if (m.getEmail() == null) { noEmail++; continue; }
                if (!m.isConsentEmail()) { noConsent++; continue; }
                if (left <= 0) { quota++; continue; }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("communityName", community.getName());
                payload.put("memberName", m.getFullName());
                payload.put("title", a.getTitle());
                payload.put("bodyHtml", a.getBody());
                payload.put("bodyText", text);
                payload.put("announcementId", a.getId().toString());
                payload.put("contactEmail", community.getContactEmail());
                queue(communityId, m.getEmail(), MEMBER_TEMPLATE, payload);
                queued++;
                if (left != Long.MAX_VALUE) left--;
            }
        }
        return finish(a, audience.size(), queued, noEmail, noConsent, quota);
    }

    /** A platform announcement: one email per distinct active community admin of the targeted active communities. Platform mail, so no community's quota. */
    public DeliveryView dispatchToCommunityAdmins(Announcement a) {
        List<String> admins = adminEmails(a);
        int queued = 0;
        if (a.isSendEmail()) {
            String text = RichText.text(a.getBody());
            for (String address : admins) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("title", a.getTitle());
                payload.put("kind", a.getKind().name());
                payload.put("bodyHtml", a.getBody());
                payload.put("bodyText", text);
                payload.put("announcementId", a.getId().toString());
                queue(null, address, ADMIN_TEMPLATE, payload);
                queued++;
            }
        }
        return finish(a, admins.size(), queued, 0, 0, 0);
    }

    public List<UUID> targetCommunityIds(Announcement a) {
        Object raw = a.getAudienceFilter().get("communityIds");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        return list.stream().map(o -> UUID.fromString(String.valueOf(o))).toList();
    }

    public int activeCommunityCount(List<UUID> ids) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT count(*) FROM communities c WHERE c.status = 'ACTIVE'" + (ids == null ? "" : " AND c.id IN (:ids)");
        if (ids != null) params.addValue("ids", ids);
        Integer n = jdbc.queryForObject(sql, params, Integer.class);
        return n == null ? 0 : n;
    }

    public List<String> adminEmails(Announcement a) {
        return adminEmails(targetCommunityIds(a));
    }

    public List<String> adminEmails(List<UUID> ids) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT DISTINCT lower(u.email::text) AS email FROM community_users cu JOIN users u ON u.id = cu.user_id JOIN communities c ON c.id = cu.community_id"
                + " WHERE c.status = 'ACTIVE' AND u.status = 'ACTIVE' AND u.role = 'COMMUNITY_ADMIN'" + (ids == null ? "" : " AND c.id IN (:ids)") + " ORDER BY 1";
        if (ids != null) params.addValue("ids", ids);
        return jdbc.query(sql, params, (rs, n) -> rs.getString("email"));
    }

    private DeliveryView finish(Announcement a, int total, int queued, int noEmail, int noConsent, int quota) {
        a.setRecipientsTotal(total);
        a.setEmailsQueued(queued);
        a.setSkippedNoEmail(noEmail);
        a.setSkippedNoConsent(noConsent);
        a.setSkippedQuota(quota);
        a.setStatus(AnnouncementStatus.SENT);
        a.setSentAt(clock.instant());
        return new DeliveryView(total, queued, noEmail, noConsent, quota);
    }

    private void queue(UUID communityId, String to, String template, Map<String, Object> payload) {
        EmailOutbox mail = new EmailOutbox();
        mail.setCommunityId(communityId);
        mail.setToEmail(to);
        mail.setTemplate(template);
        mail.setPayload(payload);
        outbox.save(mail);
    }

    /** Two bulk senders for one community (two announcements at once) must not both read the same quota. */
    private void lockEmailQuota(UUID communityId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))", new MapSqlParameterSource("key", "email-quota:" + communityId), rs -> {});
    }
}

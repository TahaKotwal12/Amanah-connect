package com.amanahconnect.announcement;

import com.amanahconnect.announcement.AnnouncementDtos.AnnouncementView;
import com.amanahconnect.announcement.AnnouncementDtos.CreateAnnouncementRequest;
import com.amanahconnect.announcement.AnnouncementDtos.DeliveryView;
import com.amanahconnect.announcement.AnnouncementDtos.PreviewView;
import com.amanahconnect.announcement.AnnouncementDtos.TestSendView;
import com.amanahconnect.announcement.AnnouncementDtos.UpdateAnnouncementRequest;
import com.amanahconnect.audit.AuditService;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.common.Text;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.notification.EmailOutbox;
import com.amanahconnect.notification.EmailOutboxRepository;
import com.amanahconnect.plan.PlanLimitService;
import com.amanahconnect.tenant.TenantGuard;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A community's announcements to its members. DRAFT (editable) → SCHEDULED (goes out by itself at its time) → SENT (final: the
 * counts of who was emailed and who was skipped, and why, are kept). Send is guarded by the row lock, so a double click or a
 * race with the schedule emails each member once. The HTML body is sanitised on the way in and only the clean version exists.
 */
@Service
@Transactional
public class AnnouncementService {

    private final AnnouncementRepository announcements;
    private final AnnouncementDispatcher dispatcher;
    private final MemberRepository members;
    private final CommunityRepository communities;
    private final UserRepository users;
    private final PlanLimitService planLimits;
    private final EmailOutboxRepository outbox;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final Clock clock;
    private final EntityManager em;

    public AnnouncementService(
            AnnouncementRepository announcements,
            AnnouncementDispatcher dispatcher,
            MemberRepository members,
            CommunityRepository communities,
            UserRepository users,
            PlanLimitService planLimits,
            EmailOutboxRepository outbox,
            AuditService audit,
            TenantGuard tenantGuard,
            Clock clock,
            EntityManager em) {
        this.announcements = announcements;
        this.dispatcher = dispatcher;
        this.members = members;
        this.communities = communities;
        this.users = users;
        this.planLimits = planLimits;
        this.outbox = outbox;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.clock = clock;
        this.em = em;
    }

    @Transactional(readOnly = true)
    public PageResponse<AnnouncementView> list(UUID communityId, AnnouncementStatus status, Pageable page) {
        var found = status == null ? announcements.findByCommunityId(communityId, page) : announcements.findByCommunityIdAndStatus(communityId, status, page);
        return PageResponse.from(found, AnnouncementViews::community);
    }

    @Transactional(readOnly = true)
    public AnnouncementView get(UUID communityId, UUID id) {
        return AnnouncementViews.community(find(communityId, id));
    }

    public AnnouncementView create(UUID communityId, CreateAnnouncementRequest request) {
        tenantGuard.requireWritable();
        Announcement a = new Announcement();
        a.setCommunityId(communityId);
        a.setTitle(title(request.title()));
        a.setBody(body(request.body()));
        applyAudience(communityId, a, request.audience() == null ? AnnouncementAudience.ALL_ACTIVE : request.audience(), request.group(), request.memberIds());
        a.setSendEmail(Boolean.TRUE.equals(request.sendEmail()));
        a.setCreatedBy(AuditService.currentActorId());
        if (request.scheduledAt() != null) {
            requireFuture(request.scheduledAt());
            a.setScheduledAt(request.scheduledAt());
            a.setStatus(AnnouncementStatus.SCHEDULED);
        }
        announcements.save(a);
        audit.record("ANNOUNCEMENT_CREATED", "Announcement", a.getId(), null, snapshot(a));
        return AnnouncementViews.community(a);
    }

    public AnnouncementView update(UUID communityId, UUID id, UpdateAnnouncementRequest request) {
        tenantGuard.requireWritable();
        Announcement a = lock(communityId, id);
        requireNotSent(a);
        Map<String, Object> before = snapshot(a);
        if (request.title() != null) a.setTitle(title(request.title()));
        if (request.body() != null) a.setBody(body(request.body()));
        if (request.audience() != null || request.group() != null || request.memberIds() != null) {
            AnnouncementAudience audience = request.audience() != null ? request.audience() : a.getAudience();
            String group = request.group() != null ? request.group() : (String) a.getAudienceFilter().get("group");
            List<UUID> ids = request.memberIds() != null ? request.memberIds() : AnnouncementDispatcher.memberIds(a);
            applyAudience(communityId, a, audience, group, ids);
        }
        if (request.sendEmail() != null) a.setSendEmail(request.sendEmail());
        if (Boolean.TRUE.equals(request.unschedule())) {
            if (request.scheduledAt() != null) throw invalid("scheduledAt", "cannot be combined with unschedule");
            a.setScheduledAt(null);
            a.setStatus(AnnouncementStatus.DRAFT);
        } else if (request.scheduledAt() != null) {
            requireFuture(request.scheduledAt());
            a.setScheduledAt(request.scheduledAt());
            a.setStatus(AnnouncementStatus.SCHEDULED);
        }
        announcements.save(a);
        audit.record("ANNOUNCEMENT_UPDATED", "Announcement", id, before, snapshot(a));
        return AnnouncementViews.community(a);
    }

    public AnnouncementView schedule(UUID communityId, UUID id, Instant at) {
        tenantGuard.requireWritable();
        Announcement a = lock(communityId, id);
        requireNotSent(a);
        requireFuture(at);
        Map<String, Object> before = snapshot(a);
        a.setScheduledAt(at);
        a.setStatus(AnnouncementStatus.SCHEDULED);
        announcements.save(a);
        audit.record("ANNOUNCEMENT_SCHEDULED", "Announcement", id, before, snapshot(a));
        return AnnouncementViews.community(a);
    }

    public AnnouncementView unschedule(UUID communityId, UUID id) {
        tenantGuard.requireWritable();
        Announcement a = lock(communityId, id);
        requireNotSent(a);
        if (a.getStatus() != AnnouncementStatus.SCHEDULED) throw new ApiException(ErrorCode.ANNOUNCEMENT_STATE, "This announcement is not scheduled.");
        Map<String, Object> before = snapshot(a);
        a.setScheduledAt(null);
        a.setStatus(AnnouncementStatus.DRAFT);
        announcements.save(a);
        audit.record("ANNOUNCEMENT_UNSCHEDULED", "Announcement", id, before, snapshot(a));
        return AnnouncementViews.community(a);
    }

    /** Sends now. The second of two simultaneous calls finds it SENT and gets a 409, so nobody is emailed twice. */
    public AnnouncementView send(UUID communityId, UUID id) {
        tenantGuard.requireWritable();
        Announcement a = lock(communityId, id);
        if (a.getStatus() == AnnouncementStatus.SENT) throw new ApiException(ErrorCode.ANNOUNCEMENT_STATE, "This announcement has already been sent.");
        Map<String, Object> before = snapshot(a);
        DeliveryView delivery = dispatcher.dispatchToMembers(a);
        announcements.save(a);
        Map<String, Object> after = snapshot(a);
        after.put("delivery", delivery);
        audit.record("ANNOUNCEMENT_SENT", "Announcement", id, before, after);
        return AnnouncementViews.community(a);
    }

    @Transactional(readOnly = true)
    public PreviewView preview(UUID communityId, UUID id) {
        Announcement a = find(communityId, id);
        List<Member> audience = dispatcher.audience(communityId, a);
        int noEmail = 0, noConsent = 0, eligible = 0;
        for (Member m : audience) {
            if (m.getEmail() == null) noEmail++;
            else if (!m.isConsentEmail()) noConsent++;
            else eligible++;
        }
        long left = planLimits.emailQuotaRemaining(communityId);
        Long quotaRemaining = left == Long.MAX_VALUE ? null : left;
        int skip = a.isSendEmail() && quotaRemaining != null ? (int) Math.max(0, eligible - quotaRemaining) : 0;
        return new PreviewView(a.getTitle(), a.getBody(), RichText.text(a.getBody()), a.getAudience(), a.isSendEmail(), audience.size(), eligible, noEmail, noConsent, quotaRemaining, skip);
    }

    /** One email of the real thing to the admin who asks, so they can see it in their own inbox. Uses one email of the quota; changes nothing else. */
    public TestSendView sendTest(UUID communityId, UUID id) {
        tenantGuard.requireWritable();
        Announcement a = find(communityId, id);
        UUID actor = AuditService.currentActorId();
        String address = users.findById(actor).map(u -> u.getEmail()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No signed-in user."));
        planLimits.checkEmailQuota(communityId); // 402 when the month's quota is used up
        Community community = communities.findById(communityId).orElseThrow();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("communityName", community.getName());
        payload.put("memberName", "(test)");
        payload.put("title", a.getTitle());
        payload.put("bodyHtml", a.getBody());
        payload.put("bodyText", RichText.text(a.getBody()));
        payload.put("announcementId", a.getId().toString());
        payload.put("contactEmail", community.getContactEmail());
        payload.put("test", true);
        EmailOutbox mail = new EmailOutbox();
        mail.setCommunityId(communityId);
        mail.setToEmail(address);
        mail.setTemplate(AnnouncementDispatcher.MEMBER_TEMPLATE);
        mail.setPayload(payload);
        outbox.save(mail);
        audit.record("ANNOUNCEMENT_TEST_SENT", "Announcement", id, null, Map.of("announcementId", id.toString()));
        return new TestSendView(maskEmail(address), true);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private Announcement find(UUID communityId, UUID id) {
        return tenantGuard.found(announcements.findByIdAndCommunityId(id, communityId));
    }

    private Announcement lock(UUID communityId, UUID id) {
        return tenantGuard.found(announcements.findWithLockByIdAndCommunityId(id, communityId));
    }

    private static void requireNotSent(Announcement a) {
        if (a.getStatus() == AnnouncementStatus.SENT) throw new ApiException(ErrorCode.ANNOUNCEMENT_NOT_EDITABLE, "This announcement has already been sent and can no longer be changed.");
    }

    private void requireFuture(Instant at) {
        if (!at.isAfter(clock.instant())) throw invalid("scheduledAt", "must be in the future (to send now, use the send action)");
    }

    private void applyAudience(UUID communityId, Announcement a, AnnouncementAudience audience, String group, List<UUID> memberIds) {
        Map<String, Object> filter = new HashMap<>();
        switch (audience) {
            case ALL_ACTIVE -> {
                if (group != null && !group.isBlank()) throw invalid("group", "only for the GROUP audience");
                if (memberIds != null && !memberIds.isEmpty()) throw invalid("memberIds", "only for the SELECTED audience");
            }
            case GROUP -> {
                String cleaned = group == null ? "" : Text.singleLine(group);
                if (cleaned.isEmpty()) throw invalid("group", "required for the GROUP audience");
                if (memberIds != null && !memberIds.isEmpty()) throw invalid("memberIds", "only for the SELECTED audience");
                filter.put("group", cleaned);
            }
            case SELECTED -> {
                if (memberIds == null || memberIds.isEmpty()) throw invalid("memberIds", "required for the SELECTED audience");
                if (group != null && !group.isBlank()) throw invalid("group", "only for the GROUP audience");
                List<UUID> distinct = new ArrayList<>(new LinkedHashSet<>(memberIds));
                if (members.findByCommunityIdAndIdInAndDeletedAtIsNull(communityId, distinct).size() != distinct.size()) {
                    throw invalid("memberIds", "contains an unknown member");
                }
                filter.put("memberIds", distinct.stream().map(UUID::toString).toList());
            }
            case COMMUNITY_ADMINS -> throw invalid("audience", "ALL_ACTIVE, GROUP or SELECTED");
        }
        a.setAudience(audience);
        a.setAudienceFilter(filter);
    }

    static String title(String value) {
        String cleaned = Text.singleLine(value);
        if (cleaned.isEmpty()) throw invalid("title", "must not be blank");
        return cleaned;
    }

    /** The only door for announcement HTML: sanitise, and refuse a body that is nothing once the unsafe parts are gone. */
    static String body(String html) {
        String clean = RichText.sanitize(html);
        if (RichText.isBlank(clean)) throw invalid("body", "must contain some text");
        return clean;
    }

    private static Map<String, Object> snapshot(Announcement a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", a.getTitle());
        map.put("status", a.getStatus().name());
        map.put("audience", a.getAudience().name());
        map.put("audienceFilter", a.getAudienceFilter());
        map.put("sendEmail", a.isSendEmail());
        map.put("scheduledAt", a.getScheduledAt() == null ? null : a.getScheduledAt().toString());
        map.put("bodyLength", a.getBody().length());
        return map;
    }

    static String maskEmail(String address) {
        int at = address.indexOf('@');
        if (at <= 1) return address;
        return address.charAt(0) + "***" + address.substring(at);
    }

    static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": " + message));
    }
}

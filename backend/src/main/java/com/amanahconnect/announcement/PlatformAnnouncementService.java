package com.amanahconnect.announcement;

import static com.amanahconnect.announcement.AnnouncementService.body;
import static com.amanahconnect.announcement.AnnouncementService.invalid;
import static com.amanahconnect.announcement.AnnouncementService.title;

import com.amanahconnect.announcement.AnnouncementDtos.CreatePlatformAnnouncementRequest;
import com.amanahconnect.announcement.AnnouncementDtos.DeliveryView;
import com.amanahconnect.announcement.AnnouncementDtos.PlatformAnnouncementView;
import com.amanahconnect.announcement.AnnouncementDtos.PlatformPreviewView;
import com.amanahconnect.announcement.AnnouncementDtos.TestSendView;
import com.amanahconnect.announcement.AnnouncementDtos.UpdatePlatformAnnouncementRequest;
import com.amanahconnect.audit.AuditService;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.notification.EmailOutbox;
import com.amanahconnect.notification.EmailOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The platform's announcements and offers to community admins (SUPER_ADMIN): to every active community or to chosen ones, as an
 * email, an in-app banner, or both. Same life cycle as a community announcement (DRAFT, SCHEDULED, SENT) and the same sanitising.
 * Platform email is not charged to any community's quota.
 */
@Service
@Transactional
public class PlatformAnnouncementService {

    private final AnnouncementRepository announcements;
    private final AnnouncementDispatcher dispatcher;
    private final UserRepository users;
    private final EmailOutboxRepository outbox;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public PlatformAnnouncementService(AnnouncementRepository announcements, AnnouncementDispatcher dispatcher, UserRepository users, EmailOutboxRepository outbox,
                                       NamedParameterJdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.announcements = announcements;
        this.dispatcher = dispatcher;
        this.users = users;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<PlatformAnnouncementView> list(AnnouncementStatus status, Pageable page) {
        var found = status == null ? announcements.findByCommunityIdIsNull(page) : announcements.findPlatformByStatus(status, page);
        return PageResponse.from(found, this::view);
    }

    @Transactional(readOnly = true)
    public PlatformAnnouncementView get(UUID id) {
        return view(announcements.findPlatformById(id).orElseThrow(NotFoundException::new));
    }

    public PlatformAnnouncementView create(CreatePlatformAnnouncementRequest request) {
        Announcement a = new Announcement();
        a.setCommunityId(null);
        a.setAudience(AnnouncementAudience.COMMUNITY_ADMINS);
        a.setTitle(title(request.title()));
        a.setBody(body(request.body()));
        a.setKind(request.kind() == null ? AnnouncementKind.ANNOUNCEMENT : request.kind());
        a.setSendEmail(Boolean.TRUE.equals(request.sendEmail()));
        a.setBanner(Boolean.TRUE.equals(request.banner()));
        setTargets(a, request.communityIds());
        setExpiry(a, request.expiresAt());
        a.setCreatedBy(AuditService.currentActorId());
        if (request.scheduledAt() != null) {
            requireFuture(request.scheduledAt());
            a.setScheduledAt(request.scheduledAt());
            a.setStatus(AnnouncementStatus.SCHEDULED);
        }
        announcements.save(a);
        audit.record("PLATFORM_ANNOUNCEMENT_CREATED", "Announcement", a.getId(), null, snapshot(a));
        return view(a);
    }

    public PlatformAnnouncementView update(UUID id, UpdatePlatformAnnouncementRequest request) {
        Announcement a = lock(id);
        requireNotSent(a);
        Map<String, Object> before = snapshot(a);
        if (request.title() != null) a.setTitle(title(request.title()));
        if (request.body() != null) a.setBody(body(request.body()));
        if (request.kind() != null) a.setKind(request.kind());
        if (request.sendEmail() != null) a.setSendEmail(request.sendEmail());
        if (request.banner() != null) a.setBanner(request.banner());
        if (Boolean.TRUE.equals(request.allCommunities())) {
            if (request.communityIds() != null && !request.communityIds().isEmpty()) throw invalid("communityIds", "cannot be combined with allCommunities");
            setTargets(a, null);
        } else if (request.communityIds() != null) {
            setTargets(a, request.communityIds());
        }
        if (Boolean.TRUE.equals(request.clearExpiry())) {
            a.setExpiresAt(null);
        } else if (request.expiresAt() != null) {
            setExpiry(a, request.expiresAt());
        }
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
        audit.record("PLATFORM_ANNOUNCEMENT_UPDATED", "Announcement", id, before, snapshot(a));
        return view(a);
    }

    public PlatformAnnouncementView send(UUID id) {
        Announcement a = lock(id);
        if (a.getStatus() == AnnouncementStatus.SENT) throw new ApiException(ErrorCode.ANNOUNCEMENT_STATE, "This announcement has already been sent.");
        Map<String, Object> before = snapshot(a);
        DeliveryView delivery = dispatcher.dispatchToCommunityAdmins(a);
        announcements.save(a);
        Map<String, Object> after = snapshot(a);
        after.put("delivery", delivery);
        audit.record("PLATFORM_ANNOUNCEMENT_SENT", "Announcement", id, before, after);
        return view(a);
    }

    @Transactional(readOnly = true)
    public PlatformPreviewView preview(UUID id) {
        Announcement a = announcements.findPlatformById(id).orElseThrow(NotFoundException::new);
        List<UUID> targets = dispatcher.targetCommunityIds(a);
        return new PlatformPreviewView(a.getTitle(), a.getBody(), RichText.text(a.getBody()), a.getKind(), dispatcher.activeCommunityCount(targets), dispatcher.adminEmails(targets).size(), a.isSendEmail(), a.isBanner());
    }

    public TestSendView sendTest(UUID id) {
        Announcement a = announcements.findPlatformById(id).orElseThrow(NotFoundException::new);
        String address = users.findById(AuditService.currentActorId()).map(u -> u.getEmail()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No signed-in user."));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", a.getTitle());
        payload.put("kind", a.getKind().name());
        payload.put("bodyHtml", a.getBody());
        payload.put("bodyText", RichText.text(a.getBody()));
        payload.put("announcementId", a.getId().toString());
        payload.put("test", true);
        EmailOutbox mail = new EmailOutbox();
        mail.setToEmail(address);
        mail.setTemplate(AnnouncementDispatcher.ADMIN_TEMPLATE);
        mail.setPayload(payload);
        outbox.save(mail);
        audit.record("PLATFORM_ANNOUNCEMENT_TEST_SENT", "Announcement", id, null, Map.of("announcementId", id.toString()));
        return new TestSendView(AnnouncementService.maskEmail(address), true);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private Announcement lock(UUID id) {
        return announcements.findPlatformWithLockById(id).orElseThrow(NotFoundException::new);
    }

    private static void requireNotSent(Announcement a) {
        if (a.getStatus() == AnnouncementStatus.SENT) throw new ApiException(ErrorCode.ANNOUNCEMENT_NOT_EDITABLE, "This announcement has already been sent and can no longer be changed.");
    }

    private void requireFuture(Instant at) {
        if (!at.isAfter(clock.instant())) throw invalid("scheduledAt", "must be in the future (to send now, use the send action)");
    }

    private void setExpiry(Announcement a, Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) throw invalid("expiresAt", "must be in the future");
        a.setExpiresAt(expiresAt);
    }

    private void setTargets(Announcement a, List<UUID> communityIds) {
        Map<String, Object> filter = new HashMap<>();
        if (communityIds != null && !communityIds.isEmpty()) {
            List<UUID> distinct = List.copyOf(new LinkedHashSet<>(communityIds));
            Integer known = jdbc.queryForObject("SELECT count(*) FROM communities WHERE id IN (:ids)", new MapSqlParameterSource("ids", distinct), Integer.class);
            if (known == null || known != distinct.size()) throw invalid("communityIds", "contains an unknown community");
            filter.put("communityIds", distinct.stream().map(UUID::toString).toList());
        }
        a.setAudienceFilter(filter);
    }

    private PlatformAnnouncementView view(Announcement a) {
        Long reads = jdbc.queryForObject("SELECT count(*) FROM announcement_reads WHERE announcement_id = :id", new MapSqlParameterSource("id", a.getId()), Long.class);
        return AnnouncementViews.platform(a, dispatcher.targetCommunityIds(a), reads == null ? 0 : reads);
    }

    private static Map<String, Object> snapshot(Announcement a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", a.getTitle());
        map.put("kind", a.getKind().name());
        map.put("status", a.getStatus().name());
        map.put("audienceFilter", a.getAudienceFilter());
        map.put("sendEmail", a.isSendEmail());
        map.put("banner", a.isBanner());
        map.put("expiresAt", a.getExpiresAt() == null ? null : a.getExpiresAt().toString());
        map.put("scheduledAt", a.getScheduledAt() == null ? null : a.getScheduledAt().toString());
        map.put("bodyLength", a.getBody().length());
        return map;
    }
}

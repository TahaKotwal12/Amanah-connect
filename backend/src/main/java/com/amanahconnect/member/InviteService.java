package com.amanahconnect.member;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.auth.AuthProperties;
import com.amanahconnect.auth.Tokens;
import com.amanahconnect.common.Text;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.member.InviteDtos.CreateInviteRequest;
import com.amanahconnect.member.InviteDtos.CreatedInviteView;
import com.amanahconnect.member.InviteDtos.EmailedInviteView;
import com.amanahconnect.member.InviteDtos.InviteByEmailRequest;
import com.amanahconnect.member.InviteDtos.InviteView;
import com.amanahconnect.notification.EmailOutbox;
import com.amanahconnect.notification.EmailOutboxRepository;
import com.amanahconnect.plan.PlanLimitService;
import com.amanahconnect.tenant.TenantGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin's side of invite links. A token is 256 random bits shown once (in the link and QR code or the email); only
 * its SHA-256 is stored, so a database leak yields no usable links.
 */
@Service
@Transactional
public class InviteService {

    static final int DEFAULT_DAYS = 14;
    static final int DEFAULT_USES = 50;
    static final int QR_SIZE = 320;

    private final MemberInviteRepository invites;
    private final CommunityRepository communities;
    private final EmailOutboxRepository outbox;
    private final PlanLimitService planLimits;
    private final AuthProperties authProperties;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final Clock clock;

    public InviteService(
            MemberInviteRepository invites,
            CommunityRepository communities,
            EmailOutboxRepository outbox,
            PlanLimitService planLimits,
            AuthProperties authProperties,
            AuditService audit,
            TenantGuard tenantGuard,
            Clock clock) {
        this.invites = invites;
        this.communities = communities;
        this.outbox = outbox;
        this.planLimits = planLimits;
        this.authProperties = authProperties;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.clock = clock;
    }

    public CreatedInviteView create(UUID communityId, CreateInviteRequest request) {
        String token = Tokens.newOpaqueToken();
        MemberInvite invite = newInvite(communityId, token, request.expiresInDays(), request.maxUses() == null ? DEFAULT_USES : request.maxUses(), request.defaultGroup(), null);
        String link = link(token);
        audit.record("MEMBER_INVITE_CREATED", "MemberInvite", invite.getId(), null, auditView(invite));
        String qr = Base64.getEncoder().encodeToString(QrCodes.png(link, QR_SIZE));
        return new CreatedInviteView(view(invite), link, qr);
    }

    /** Creates a single-use link and emails it. The link is only ever in that email. */
    public EmailedInviteView inviteByEmail(UUID communityId, InviteByEmailRequest request) {
        Community community = communities.findById(communityId).orElseThrow(NotFoundException::new);
        planLimits.checkEmailQuota(communityId);
        String token = Tokens.newOpaqueToken();
        String email = request.email().trim();
        MemberInvite invite = newInvite(communityId, token, request.expiresInDays(), 1, request.defaultGroup(), email);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("communityName", community.getName());
        payload.put("recipientName", Text.blankToNull(Text.singleLine(request.name())));
        payload.put("link", link(token));
        payload.put("expiresAt", invite.getExpiresAt().toString());
        EmailOutbox mail = new EmailOutbox();
        mail.setCommunityId(communityId);
        mail.setToEmail(email);
        mail.setTemplate("member-invite");
        mail.setPayload(payload);
        outbox.save(mail);

        Map<String, Object> after = auditView(invite);
        after.put("emailed", true);
        audit.record("MEMBER_INVITE_EMAILED", "MemberInvite", invite.getId(), null, after);
        return new EmailedInviteView(view(invite));
    }

    @Transactional(readOnly = true)
    public PageResponse<InviteView> list(UUID communityId, String state, Pageable pageable) {
        return PageResponse.from(invites.searchByCommunityId(communityId, state == null ? "ALL" : state, clock.instant(), pageable), this::view);
    }

    public InviteView revoke(UUID communityId, UUID id) {
        MemberInvite invite = tenantGuard.found(invites.findByIdAndCommunityId(id, communityId));
        if (invite.getRevokedAt() != null) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This invitation link is already revoked.");
        }
        invite.setRevokedAt(clock.instant());
        invites.save(invite);
        audit.record("MEMBER_INVITE_REVOKED", "MemberInvite", id, Map.of("state", "ACTIVE"), Map.of("state", "REVOKED"));
        return view(invite);
    }

    private MemberInvite newInvite(UUID communityId, String token, Integer expiresInDays, int maxUses, String defaultGroup, String invitedEmail) {
        MemberInvite invite = new MemberInvite();
        invite.setCommunityId(communityId);
        invite.setTokenHash(Tokens.sha256Hex(token));
        invite.setExpiresAt(clock.instant().plus(Duration.ofDays(expiresInDays == null ? DEFAULT_DAYS : expiresInDays)));
        invite.setMaxUses(maxUses);
        invite.setDefaultGroupLabel(Text.blankToNull(Text.singleLine(defaultGroup)));
        invite.setInvitedEmail(invitedEmail);
        invite.setCreatedBy(AuditService.currentActorId());
        invites.save(invite);
        return invite;
    }

    private String link(String token) {
        return authProperties.frontendBaseUrl().replaceAll("/+$", "") + "/join/" + token;
    }

    static String stateOf(MemberInvite invite, Instant now) {
        if (invite.getRevokedAt() != null) return "REVOKED";
        if (!invite.getExpiresAt().isAfter(now)) return "EXPIRED";
        if (invite.getUsedCount() >= invite.getMaxUses()) return "USED_UP";
        return "ACTIVE";
    }

    private InviteView view(MemberInvite i) {
        return new InviteView(i.getId(), stateOf(i, clock.instant()), i.getExpiresAt(), i.getMaxUses(), i.getUsedCount(), i.getDefaultGroupLabel(),
                i.getInvitedEmail(), i.getRevokedAt(), i.getCreatedAt());
    }

    private static Map<String, Object> auditView(MemberInvite i) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("expiresAt", i.getExpiresAt().toString());
        map.put("maxUses", i.getMaxUses());
        map.put("defaultGroup", i.getDefaultGroupLabel());
        return map;
    }
}

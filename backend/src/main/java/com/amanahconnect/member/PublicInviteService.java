package com.amanahconnect.member;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.auth.AuthProperties;
import com.amanahconnect.auth.Tokens;
import com.amanahconnect.common.Text;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.community.CommunityStatus;
import com.amanahconnect.community.settings.SettingsService;
import com.amanahconnect.file.ObjectStorage;
import com.amanahconnect.member.InviteDtos.FormField;
import com.amanahconnect.member.InviteDtos.PublicInviteView;
import com.amanahconnect.member.InviteDtos.PublicRegisterRequest;
import com.amanahconnect.notification.PlatformEmails;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public side of invite links. Whatever is wrong with a link (never existed, expired, revoked, used up, or its
 * community is not active) the answer is the same 404 INVITE_UNAVAILABLE, so the page reveals nothing about which
 * communities exist. A valid link shows only the community's name, logo and the form.
 */
@Service
@Transactional
public class PublicInviteService {

    public static final String REGISTRATION_RECEIVED = "member-registration-received";

    private static final Logger log = LoggerFactory.getLogger(PublicInviteService.class);
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final MemberInviteRepository invites;
    private final MemberRegistrationRepository registrations;
    private final CommunityRepository communities;
    private final MemberLock lock;
    private final PlatformEmails platformEmails;
    private final ObjectStorage storage;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuthProperties authProperties;
    private final AuditService audit;
    private final Clock clock;

    public PublicInviteService(
            MemberInviteRepository invites,
            MemberRegistrationRepository registrations,
            CommunityRepository communities,
            MemberLock lock,
            PlatformEmails platformEmails,
            ObjectStorage storage,
            NamedParameterJdbcTemplate jdbc,
            AuthProperties authProperties,
            AuditService audit,
            Clock clock) {
        this.invites = invites;
        this.registrations = registrations;
        this.communities = communities;
        this.lock = lock;
        this.platformEmails = platformEmails;
        this.storage = storage;
        this.jdbc = jdbc;
        this.authProperties = authProperties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublicInviteView view(String token) {
        MemberInvite invite = usable(lookup(token, false));
        Community community = community(invite);
        String logoUrl = null;
        if (community.getLogoKey() != null) {
            try {
                logoUrl = storage.presignDownload(community.getLogoKey());
            } catch (RuntimeException e) {
                log.warn("Could not sign a logo URL for an invite page: {}", e.toString());
            }
        }
        Object label = community.getSettings().get(SettingsService.GROUP_LABEL_KEY);
        String groupLabel = label instanceof String s && !s.isBlank() ? s : SettingsService.DEFAULT_GROUP_LABEL;
        String defaultGroup = invite.getDefaultGroupLabel();
        java.util.ArrayList<FormField> fields = new java.util.ArrayList<>();
        fields.add(new FormField("fullName", "Full name", "text", true, 150));
        fields.add(new FormField("email", "Email", "email", true, 254));
        fields.add(new FormField("phone", "Phone", "tel", false, 30));
        if (defaultGroup == null) {
            fields.add(new FormField("group", groupLabel, "text", false, 100));
        }
        fields.add(new FormField("consentEmail", "I agree to receive emails from " + community.getName(), "checkbox", false, null));
        return new PublicInviteView(community.getName(), logoUrl, groupLabel, defaultGroup, List.copyOf(fields));
    }

    /**
     * Records a PENDING registration for the admin to review. Always looks the same to the visitor: a filled honeypot,
     * a repeat of an address that is already waiting, and a real new registration all answer success.
     */
    public void register(String token, PublicRegisterRequest request) {
        MemberInvite invite = usable(lookup(token, true));
        if (request.website() != null && !request.website().isBlank()) {
            log.info("Registration honeypot triggered; submission dropped");
            return;
        }
        String name = Text.singleLine(request.fullName());
        String email = Text.singleLine(request.email());
        if (name.isEmpty() || email.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("fullName and email are required"));
        }
        UUID communityId = invite.getCommunityId();
        Community community = community(invite);
        lock.lock(communityId); // registrations in one community are recorded one at a time
        if (registrations.existsByCommunityIdAndEmailAndStatus(communityId, email, RegistrationStatus.PENDING)) {
            return; // already waiting: no second row, and no use of the link is consumed
        }
        MemberRegistration registration = new MemberRegistration();
        registration.setCommunityId(communityId);
        registration.setInvite(invite);
        registration.setFullName(name);
        registration.setEmail(email);
        registration.setPhone(Text.blankToNull(Text.singleLine(request.phone())));
        registration.setGroupLabel(invite.getDefaultGroupLabel() != null ? invite.getDefaultGroupLabel() : Text.blankToNull(Text.singleLine(request.group())));
        registration.setConsentEmail(Boolean.TRUE.equals(request.consentEmail()));
        registrations.save(registration);
        invite.setUsedCount(invite.getUsedCount() + 1);
        invites.save(invite);

        notifyAdmins(communityId, community.getName(), name);
        // No personal data in the audit entry: the registration row holds it.
        audit.record("MEMBER_REGISTRATION_RECEIVED", null, communityId, "MemberRegistration", registration.getId(), null, Map.of("inviteId", invite.getId().toString()));
    }

    private void notifyAdmins(UUID communityId, String communityName, String applicantName) {
        List<String> admins = jdbc.queryForList(
                "SELECT u.email::text FROM community_users cu JOIN users u ON u.id = cu.user_id WHERE cu.community_id = :c AND u.status = 'ACTIVE' ORDER BY u.email",
                new MapSqlParameterSource("c", communityId), String.class);
        for (String admin : admins) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("communityName", communityName);
            payload.put("applicantName", applicantName);
            payload.put("reviewLink", authProperties.frontendBaseUrl().replaceAll("/+$", "") + "/members/registrations");
            platformEmails.toAddress(REGISTRATION_RECEIVED, admin, payload);
        }
    }

    private MemberInvite lookup(String token, boolean forUpdate) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw unavailable();
        }
        String hash = Tokens.sha256Hex(token);
        return (forUpdate ? invites.findWithLockByTokenHash(hash) : invites.findByTokenHash(hash)).orElseThrow(PublicInviteService::unavailable);
    }

    private MemberInvite usable(MemberInvite invite) {
        if (!"ACTIVE".equals(InviteService.stateOf(invite, clock.instant())) || community(invite).getStatus() != CommunityStatus.ACTIVE) {
            throw unavailable();
        }
        return invite;
    }

    private Community community(MemberInvite invite) {
        return communities.findById(invite.getCommunityId()).orElseThrow(PublicInviteService::unavailable);
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.INVITE_UNAVAILABLE, "This invitation link is not valid or has expired.");
    }
}

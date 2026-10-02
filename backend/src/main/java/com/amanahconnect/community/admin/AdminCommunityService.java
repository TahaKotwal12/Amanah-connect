package com.amanahconnect.community.admin;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.auth.InvitationService;
import com.amanahconnect.auth.PasswordService;
import com.amanahconnect.auth.User;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.auth.UserRole;
import com.amanahconnect.auth.UserStatus;
import com.amanahconnect.common.FinancialYear;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.community.CommunityRole;
import com.amanahconnect.community.CommunityStatus;
import com.amanahconnect.community.CommunityUser;
import com.amanahconnect.community.CommunityUserRepository;
import com.amanahconnect.community.admin.AdminCommunityDtos.*;
import com.amanahconnect.lead.Lead;
import com.amanahconnect.lead.LeadRepository;
import com.amanahconnect.lead.LeadStatus;
import com.amanahconnect.notification.PlatformEmails;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.plan.PlanRepository;
import com.amanahconnect.plan.PlatformSubscription;
import com.amanahconnect.plan.PlatformSubscriptionRepository;
import com.amanahconnect.plan.SubscriptionProperties;
import com.amanahconnect.plan.SubscriptionStatusRules;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Super-admin operations on communities. Every mutation writes its audit record in the same transaction.
 *
 * <p>Super admins have no tenant context, so every query here passes the community id explicitly. The
 * support overview is read-only on community data and is itself audited ({@code SUPPORT_VIEW}).
 */
@Service
@Transactional
public class AdminCommunityService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CommunityRepository communities;
    private final UserRepository users;
    private final CommunityUserRepository communityUsers;
    private final PlanRepository plans;
    private final LeadRepository leads;
    private final PlatformSubscriptionRepository subscriptions;
    private final InvitationService invitations;
    private final PasswordService passwords;
    private final AuditService audit;
    private final PlatformEmails emails;
    private final AdminCommunityQueries queries;
    private final SubscriptionProperties subscriptionProperties;
    private final EntityManager em;
    private final Clock clock;

    public AdminCommunityService(
            CommunityRepository communities,
            UserRepository users,
            CommunityUserRepository communityUsers,
            PlanRepository plans,
            LeadRepository leads,
            PlatformSubscriptionRepository subscriptions,
            InvitationService invitations,
            PasswordService passwords,
            AuditService audit,
            PlatformEmails emails,
            AdminCommunityQueries queries,
            SubscriptionProperties subscriptionProperties,
            EntityManager em,
            Clock clock) {
        this.communities = communities;
        this.users = users;
        this.communityUsers = communityUsers;
        this.plans = plans;
        this.leads = leads;
        this.subscriptions = subscriptions;
        this.invitations = invitations;
        this.passwords = passwords;
        this.audit = audit;
        this.emails = emails;
        this.queries = queries;
        this.subscriptionProperties = subscriptionProperties;
        this.em = em;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ---- create ------------------------------------------------------------------------------

    public CommunityView create(CreateCommunityRequest request) {
        Plan plan = activePlan(request.planId());
        String ownerEmail = request.ownerEmail().trim();
        if (users.findByEmail(ownerEmail).isPresent()) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED, "A user with this email address already exists.");
        }
        String slug;
        if (request.slug() != null && !request.slug().isBlank()) {
            if (communities.existsBySlug(request.slug())) {
                throw new ApiException(ErrorCode.SLUG_TAKEN, "This slug is already in use.");
            }
            slug = request.slug();
        } else {
            slug = SlugGenerator.unique(request.name(), communities::existsBySlug);
        }
        Lead lead = null;
        if (request.leadId() != null) {
            lead = leads.findById(request.leadId()).orElseThrow(() -> invalid("leadId", "unknown lead"));
            if (lead.getStatus() == LeadStatus.CONVERTED) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This lead has already been converted to a community.");
            }
        }

        Community community = new Community();
        community.setName(request.name().trim());
        community.setSlug(slug);
        community.setPlan(plan);
        community.setStatus(CommunityStatus.PENDING);
        community.setContactName(trimToNull(request.contactName()));
        community.setContactEmail(trimToNull(request.contactEmail()));
        community.setContactPhone(trimToNull(request.contactPhone()));
        community.setAddressLine1(trimToNull(request.addressLine1()));
        community.setAddressLine2(trimToNull(request.addressLine2()));
        community.setCity(trimToNull(request.city()));
        community.setState(trimToNull(request.state()));
        community.setPostalCode(trimToNull(request.postalCode()));
        if (request.country() != null) {
            community.setCountry(request.country());
        }
        community.setDateOfEstablishment(request.dateOfEstablishment());
        if (request.financialYearStartMonth() != null) {
            community.setFinancialYearStartMonth(request.financialYearStartMonth().shortValue());
        }
        community.setStatusChangedAt(clock.instant());
        community.setStatusChangedBy(AuditService.currentActorId());
        communities.save(community);

        User owner = new User();
        owner.setEmail(ownerEmail);
        owner.setFullName(request.ownerName().trim());
        owner.setRole(UserRole.COMMUNITY_ADMIN);
        owner.setStatus(UserStatus.INVITED);
        users.save(owner);

        CommunityUser link = new CommunityUser();
        link.setCommunityId(community.getId());
        link.setUser(owner);
        link.setRole(CommunityRole.OWNER);
        communityUsers.save(link);
        community.setOwner(owner);

        try {
            em.flush();
        } catch (RuntimeException e) {
            throw translateConflict(e);
        }

        UUID actor = AuditService.currentActorId();
        UUID communityId = community.getId();
        audit.recordForCommunity("COMMUNITY_CREATED", communityId, "Community", communityId, null, snapshot(community));
        if (lead != null) {
            lead.setStatus(LeadStatus.CONVERTED);
            lead.setConvertedCommunityId(communityId);
            lead.setHandledBy(actor);
            audit.recordForCommunity("LEAD_CONVERTED", communityId, "Lead", lead.getId(), Map.of("status", "NEW"), Map.of("status", "CONVERTED", "communityId", communityId.toString()));
        }
        // Sending the invitation retires older tokens with a bulk update, which detaches loaded entities,
        // so it goes last and the response is read back fresh.
        invitations.invite(owner, actor);
        return get(communityId);
    }

    // ---- read --------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<CommunityListItem> list(AdminCommunityQueries.Filter filter, PageRequest page) {
        return queries.list(filter, page, today(), subscriptionProperties.expiringWindowDays());
    }

    @Transactional(readOnly = true)
    public CommunityView get(UUID id) {
        return view(find(id));
    }

    // ---- update ------------------------------------------------------------------------------

    public CommunityView update(UUID id, UpdateCommunityRequest request) {
        Community community = find(id);
        if (request.version() != null && request.version() != community.getVersion()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "The community was changed by someone else. Reload and try again.");
        }
        Map<String, Object> before = snapshot(community);
        if (request.name() != null) community.setName(request.name().trim());
        if (request.contactName() != null) community.setContactName(trimToNull(request.contactName()));
        if (request.contactEmail() != null) community.setContactEmail(trimToNull(request.contactEmail()));
        if (request.contactPhone() != null) community.setContactPhone(trimToNull(request.contactPhone()));
        if (request.addressLine1() != null) community.setAddressLine1(trimToNull(request.addressLine1()));
        if (request.addressLine2() != null) community.setAddressLine2(trimToNull(request.addressLine2()));
        if (request.city() != null) community.setCity(trimToNull(request.city()));
        if (request.state() != null) community.setState(trimToNull(request.state()));
        if (request.postalCode() != null) community.setPostalCode(trimToNull(request.postalCode()));
        if (request.country() != null) community.setCountry(request.country());
        if (request.dateOfEstablishment() != null) community.setDateOfEstablishment(request.dateOfEstablishment());
        if (request.planId() != null && !request.planId().equals(community.getPlan().getId())) {
            community.setPlan(activePlan(request.planId()));
        }
        if (request.require2fa() != null) {
            Map<String, Object> settings = new HashMap<>(community.getSettings());
            settings.put("require_2fa", request.require2fa());
            community.setSettings(settings);
        }
        audit.recordForCommunity("COMMUNITY_UPDATED", id, "Community", id, before, snapshot(community));
        try {
            em.flush();
        } catch (RuntimeException e) {
            throw translateConflict(e);
        }
        return view(community);
    }

    // ---- status ------------------------------------------------------------------------------

    public CommunityView suspend(UUID id, String reason) {
        return changeStatus(id, "COMMUNITY_SUSPENDED", CommunityStatusMachine::suspend, reason, PlatformEmails.COMMUNITY_SUSPENDED);
    }

    public CommunityView activate(UUID id, String reason) {
        return changeStatus(id, "COMMUNITY_ACTIVATED", CommunityStatusMachine::activate, reason, PlatformEmails.COMMUNITY_ACTIVATED);
    }

    public CommunityView archive(UUID id, String reason) {
        return changeStatus(id, "COMMUNITY_ARCHIVED", CommunityStatusMachine::archive, reason, null);
    }

    private CommunityView changeStatus(
            UUID id, String action, java.util.function.UnaryOperator<CommunityStatus> transition, String reason, String ownerEmailTemplate) {
        Community community = find(id);
        CommunityStatus from = community.getStatus();
        CommunityStatus to = transition.apply(from);
        String cleanReason = trimToNull(reason);
        community.setStatus(to);
        community.setStatusReason(cleanReason);
        community.setStatusChangedAt(clock.instant());
        community.setStatusChangedBy(AuditService.currentActorId());
        Map<String, Object> before = new LinkedHashMap<>(Map.of("status", from.name()));
        Map<String, Object> after = new LinkedHashMap<>(Map.of("status", to.name()));
        if (cleanReason != null) {
            after.put("reason", cleanReason);
        }
        audit.recordForCommunity(action, id, "Community", id, before, after);
        if (ownerEmailTemplate != null && community.getOwner() != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("communityName", community.getName());
            payload.put("status", to.name());
            payload.put("reason", cleanReason == null ? "" : cleanReason);
            emails.toAddress(ownerEmailTemplate, community.getOwner().getEmail(), payload);
        }
        return view(community);
    }

    // ---- admin password reset ------------------------------------------------------------------

    public record ResetOutcome(UUID userId, String action) {}

    public ResetOutcome resetAdminPassword(UUID communityId, UUID userId) {
        Community community = find(communityId);
        User target;
        if (userId != null) {
            boolean administers = communityUsers.findByCommunityId(communityId).stream().anyMatch(l -> l.getUser().getId().equals(userId));
            if (!administers) {
                throw new NotFoundException();
            }
            target = users.findById(userId).orElseThrow(NotFoundException::new);
        } else {
            target = community.getOwner();
            if (target == null) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This community has no owner to reset.");
            }
        }
        UUID targetId = target.getId();
        UserStatus status = target.getStatus();
        if (status == UserStatus.DISABLED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This account is disabled.");
        }
        String action = status == UserStatus.INVITED ? "INVITATION_RESENT" : "RESET_EMAIL_SENT";
        audit.recordForCommunity("COMMUNITY_ADMIN_PASSWORD_RESET", communityId, "User", targetId, null, Map.of("action", action));
        UUID actor = AuditService.currentActorId();
        // Both calls below issue bulk updates (detaching entities), so nothing is read after them.
        if (status == UserStatus.INVITED) {
            invitations.invite(target, actor);
        } else {
            passwords.adminInitiatedReset(target, actor);
        }
        return new ResetOutcome(targetId, action);
    }

    // ---- export and support overview -------------------------------------------------------------

    public CommunityExport export(UUID id) {
        Community community = find(id);
        CommunityView profile = view(community);
        long members = queries.memberCount(id);
        List<SubscriptionLine> history = subscriptionLines(id);
        audit.recordForCommunity("COMMUNITY_EXPORTED", id, "Community", id, null, Map.of("membersCount", members, "subscriptions", history.size()));
        return new CommunityExport(clock.instant(), profile, members, history);
    }

    /** Read-only view for support. Every call is audited as SUPPORT_VIEW, whoever looks and however often. */
    public CommunityOverview overview(UUID id) {
        Community community = find(id);
        audit.recordForCommunity("SUPPORT_VIEW", id, "Community", id, null, Map.of("view", "overview"));
        LocalDate fyStart = FinancialYear.startOf(today(), community.getFinancialYearStartMonth());
        return new CommunityOverview(view(community), queries.counts(id), queries.finance(id, fyStart), queries.recentActivity(id, 20));
    }

    @Transactional(readOnly = true)
    public List<SubscriptionLine> subscriptionHistory(UUID id) {
        find(id);
        return subscriptionLines(id);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private List<SubscriptionLine> subscriptionLines(UUID communityId) {
        List<PlatformSubscription> all = subscriptions.findByCommunityIdOrderByPeriodEndDesc(communityId);
        LocalDate today = today();
        int window = subscriptionProperties.expiringWindowDays();
        return all.stream()
                .map(s -> {
                    boolean renewed = all.stream().anyMatch(o -> o != s && o.getStatus().name().equals("ACTIVE") && o.getPeriodEnd().isAfter(s.getPeriodEnd()));
                    return new SubscriptionLine(
                            s.getId(), s.getPlan().getCode(), s.getPlan().getName(), s.getPeriodStart(), s.getPeriodEnd(),
                            Money.of(s.getAmount()), s.getPaidOn(), s.getReference(),
                            SubscriptionStatusRules.display(s.getStatus().name(), s.getPeriodEnd(), today, window, renewed));
                })
                .toList();
    }

    private Community find(UUID id) {
        return communities.findById(id).orElseThrow(NotFoundException::new);
    }

    private Plan activePlan(UUID planId) {
        Plan plan = plans.findById(planId).orElseThrow(() -> invalid("planId", "unknown plan"));
        if (!plan.isActive()) {
            throw new ApiException(ErrorCode.PLAN_INACTIVE, "The " + plan.getName() + " plan is no longer offered. Choose an active plan.");
        }
        return plan;
    }

    private CommunityView view(Community c) {
        User owner = c.getOwner();
        Plan plan = c.getPlan();
        Map<String, Object> latest = queries.latestSubscription(c.getId());
        LocalDate endsOn = latest == null ? null : ((java.sql.Date) latest.get("period_end")).toLocalDate();
        String subStatus = latest == null ? "NONE" : SubscriptionStatusRules.display((String) latest.get("status"), endsOn, today(), subscriptionProperties.expiringWindowDays(), false);
        return new CommunityView(
                c.getId(), c.getName(), c.getSlug(), c.getStatus().name(), c.getStatusReason(), c.getStatusChangedAt(),
                c.getContactName(), c.getContactEmail(), c.getContactPhone(), c.getAddressLine1(), c.getAddressLine2(),
                c.getCity(), c.getState(), c.getPostalCode(), c.getCountry(), c.getDateOfEstablishment(),
                new PlanRef(plan.getId(), plan.getCode(), plan.getName()), c.getCurrency(), c.getFinancialYearStartMonth(),
                Boolean.TRUE.equals(c.getSettings().get("require_2fa")),
                owner == null ? null : new OwnerView(owner.getId(), owner.getFullName(), owner.getEmail(), owner.getStatus().name()),
                queries.memberCount(c.getId()), endsOn, subStatus, c.getVersion(), c.getCreatedAt());
    }

    /** The auditable shape of a community: business fields only. */
    private static Map<String, Object> snapshot(Community c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", c.getName());
        map.put("slug", c.getSlug());
        map.put("status", c.getStatus().name());
        map.put("plan", c.getPlan().getCode());
        map.put("contactName", c.getContactName());
        map.put("contactEmail", c.getContactEmail());
        map.put("contactPhone", c.getContactPhone());
        map.put("city", c.getCity());
        map.put("state", c.getState());
        map.put("country", c.getCountry());
        map.put("require2fa", Boolean.TRUE.equals(c.getSettings().get("require_2fa")));
        return map;
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": " + message));
    }

    private static ApiException translateConflict(RuntimeException e) {
        String text = String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : e.getCause().getMessage());
        if (text.contains("uq_users_email")) {
            return new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED, "A user with this email address already exists.");
        }
        if (text.contains("uq_communities_slug")) {
            return new ApiException(ErrorCode.SLUG_TAKEN, "This slug is already in use.");
        }
        if (e instanceof DataIntegrityViolationException || e.getClass().getSimpleName().contains("ConstraintViolation")) {
            return new ApiException(ErrorCode.VALIDATION_FAILED, "The data conflicts with an existing record.");
        }
        throw e;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}

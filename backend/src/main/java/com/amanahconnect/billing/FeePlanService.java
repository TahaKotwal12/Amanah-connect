package com.amanahconnect.billing;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.billing.BillingDtos.CreateFeePlanRequest;
import com.amanahconnect.billing.BillingDtos.FeePlanView;
import com.amanahconnect.billing.BillingDtos.UpdateFeePlanRequest;
import com.amanahconnect.common.Text;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.tenant.TenantGuard;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fee plans: what is charged, how often, and to whom. A plan is never deleted (invoices point at it); it is switched off.
 * Changing the amount affects future invoices only, never ones already issued.
 */
@Service
@Transactional
public class FeePlanService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final FeePlanRepository plans;
    private final MemberRepository members;
    private final CommunityRepository communities;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final Clock clock;

    public FeePlanService(FeePlanRepository plans, MemberRepository members, CommunityRepository communities, AuditService audit, TenantGuard tenantGuard, Clock clock) {
        this.plans = plans;
        this.members = members;
        this.communities = communities;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<FeePlanView> list(UUID communityId, Boolean active) {
        int fyMonth = fyMonth(communityId);
        return plans.findByCommunityId(communityId, org.springframework.data.domain.PageRequest.of(0, 500, Sort.by("name", "id"))).stream()
                .filter(p -> active == null || p.isActive() == active)
                .map(p -> view(p, fyMonth))
                .toList();
    }

    @Transactional(readOnly = true)
    public FeePlanView get(UUID communityId, UUID id) {
        return view(find(communityId, id), fyMonth(communityId));
    }

    public FeePlanView create(UUID communityId, CreateFeePlanRequest request) {
        if (request.kind() == FeeKind.MEMBERSHIP) {
            throw invalid("kind", "MEMBERSHIP is only used for imported invoices; choose MAINTENANCE, SUBSCRIPTION, DONATION, EVENT, FINE or OTHER");
        }
        FeePlan plan = new FeePlan();
        plan.setCommunityId(communityId);
        plan.setName(Text.singleLine(request.name()));
        plan.setKind(request.kind());
        plan.setAmount(Money.of(request.amount()).amount());
        plan.setFrequency(request.frequency());
        plan.setDueDay(request.dueDay() == null ? null : request.dueDay().shortValue());
        applyAudience(communityId, plan, request.appliesTo() == null ? FeeAudience.ALL_ACTIVE : request.appliesTo(), request.group(), request.memberIds());
        int fyMonth = fyMonth(communityId);
        boolean auto = Boolean.TRUE.equals(request.autoGenerate());
        if (auto && request.frequency() == FeeFrequency.ONE_TIME) {
            throw invalid("autoGenerate", "a one-time plan cannot bill automatically");
        }
        plan.setAutoGenerate(auto);
        if (auto) {
            // Automatic billing starts with the NEXT period, so creating a plan never surprises members mid-period.
            plan.setLastGeneratedPeriod(currentPeriodLabel(plan, fyMonth));
        }
        plans.save(plan);
        audit.record("FEE_PLAN_CREATED", "FeePlan", plan.getId(), null, snapshot(plan));
        return view(plan, fyMonth);
    }

    public FeePlanView update(UUID communityId, UUID id, UpdateFeePlanRequest request) {
        FeePlan plan = find(communityId, id);
        Map<String, Object> before = snapshot(plan);
        int fyMonth = fyMonth(communityId);
        if (request.name() != null) plan.setName(Text.singleLine(request.name()));
        if (request.amount() != null) plan.setAmount(Money.of(request.amount()).amount());
        if (request.dueDay() != null) plan.setDueDay(request.dueDay().shortValue());
        if (request.appliesTo() != null || request.group() != null || request.memberIds() != null) {
            FeeAudience audience = request.appliesTo() != null ? request.appliesTo() : plan.getAppliesTo();
            String group = request.group() != null ? request.group() : (String) plan.getAppliesToFilter().get("group");
            List<UUID> ids = request.memberIds() != null ? request.memberIds() : memberIdsOf(plan);
            applyAudience(communityId, plan, audience, group, ids);
        }
        if (request.active() != null) plan.setActive(request.active());
        if (request.autoGenerate() != null && request.autoGenerate() != plan.isAutoGenerate()) {
            if (request.autoGenerate() && plan.getFrequency() == FeeFrequency.ONE_TIME) {
                throw invalid("autoGenerate", "a one-time plan cannot bill automatically");
            }
            plan.setAutoGenerate(request.autoGenerate());
            if (request.autoGenerate()) {
                plan.setLastGeneratedPeriod(currentPeriodLabel(plan, fyMonth)); // from the next period on
            }
        }
        plans.save(plan);
        audit.record("FEE_PLAN_UPDATED", "FeePlan", id, before, snapshot(plan));
        return view(plan, fyMonth);
    }

    // ---- shared with invoice generation --------------------------------------------------------------------------

    public FeePlan find(UUID communityId, UUID id) {
        return tenantGuard.found(plans.findByIdAndCommunityId(id, communityId));
    }

    static List<UUID> memberIdsOf(FeePlan plan) {
        Object ids = plan.getAppliesToFilter().get("memberIds");
        List<UUID> result = new ArrayList<>();
        if (ids instanceof List<?> list) {
            list.forEach(o -> result.add(UUID.fromString(o.toString())));
        }
        return result;
    }

    String currentPeriodLabel(FeePlan plan, int fyMonth) {
        FeePeriods.Period period = FeePeriods.current(plan.getFrequency(), LocalDate.now(clock.withZone(IST)), fyMonth);
        return period == null ? null : period.label();
    }

    public int fyMonth(UUID communityId) {
        Community community = communities.findById(communityId).orElseThrow(NotFoundException::new);
        return community.getFinancialYearStartMonth();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void applyAudience(UUID communityId, FeePlan plan, FeeAudience audience, String group, List<UUID> memberIds) {
        Map<String, Object> filter = new LinkedHashMap<>();
        switch (audience) {
            case ALL_ACTIVE -> { /* no filter */ }
            case GROUP -> {
                String clean = group == null ? "" : Text.singleLine(group);
                if (clean.isEmpty()) {
                    throw invalid("group", "required when the plan applies to a group");
                }
                filter.put("group", clean);
            }
            case SELECTED -> {
                Set<UUID> distinct = new LinkedHashSet<>(memberIds == null ? List.of() : memberIds);
                if (distinct.isEmpty()) {
                    throw invalid("memberIds", "required when the plan applies to selected members");
                }
                // Missing and other-community ids are indistinguishable: both are "unknown".
                if (members.findByCommunityIdAndIdInAndDeletedAtIsNull(communityId, distinct).size() != distinct.size()) {
                    throw invalid("memberIds", "contains an unknown member");
                }
                filter.put("memberIds", distinct.stream().map(UUID::toString).toList());
            }
        }
        plan.setAppliesTo(audience);
        plan.setAppliesToFilter(filter);
    }

    private FeePlanView view(FeePlan p, int fyMonth) {
        String group = (String) p.getAppliesToFilter().get("group");
        return new FeePlanView(
                p.getId(), p.getName(), p.getKind(), Money.of(p.getAmount()), p.getFrequency(), p.getDueDay() == null ? null : p.getDueDay().intValue(),
                p.getAppliesTo(), group, p.getAppliesTo() == FeeAudience.SELECTED ? memberIdsOf(p) : List.of(), p.isActive(), p.isAutoGenerate(),
                p.getLastGeneratedPeriod(), currentPeriodLabel(p, fyMonth), p.getCreatedAt());
    }

    private static Map<String, Object> snapshot(FeePlan p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", p.getName());
        map.put("kind", p.getKind() == null ? null : p.getKind().name());
        map.put("amount", p.getAmount() == null ? null : p.getAmount().toPlainString());
        map.put("frequency", p.getFrequency() == null ? null : p.getFrequency().name());
        map.put("dueDay", p.getDueDay());
        map.put("appliesTo", p.getAppliesTo() == null ? null : p.getAppliesTo().name());
        map.put("group", p.getAppliesToFilter().get("group"));
        map.put("memberCount", p.getAppliesToFilter().get("memberIds") instanceof List<?> l ? l.size() : null);
        map.put("active", p.isActive());
        map.put("autoGenerate", p.isAutoGenerate());
        return map;
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": " + message));
    }
}

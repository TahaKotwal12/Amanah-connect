package com.amanahconnect.plan.admin;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.plan.PlanRepository;
import com.amanahconnect.plan.admin.AdminPlanDtos.CreatePlanRequest;
import com.amanahconnect.plan.admin.AdminPlanDtos.PlanView;
import com.amanahconnect.plan.admin.AdminPlanDtos.UpdatePlanRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan administration.
 *
 * <p>Deactivating a plan only stops it being offered (new communities, new subscriptions, the public
 * list). Communities already on it keep it, and its limits and features keep applying, because
 * {@code PlanLimitService} reads the community's own plan whether or not it is active.
 */
@Service
@Transactional
public class AdminPlanService {

    private final PlanRepository plans;
    private final CommunityRepository communities;
    private final AuditService audit;

    public AdminPlanService(PlanRepository plans, CommunityRepository communities, AuditService audit) {
        this.plans = plans;
        this.communities = communities;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PlanView> list(Boolean active, Boolean publicPlan) {
        return plans.findAll(Sort.by("sortOrder", "code")).stream()
                .filter(p -> active == null || p.isActive() == active)
                .filter(p -> publicPlan == null || p.isPublicPlan() == publicPlan)
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public PlanView get(UUID id) {
        return view(find(id));
    }

    public PlanView create(CreatePlanRequest request) {
        PlanDefinitionValidator.validate(request.limits(), request.features());
        if (plans.existsByCode(request.code())) {
            throw new ApiException(ErrorCode.CODE_TAKEN, "A plan with this code already exists.");
        }
        Plan plan = new Plan();
        plan.setCode(request.code());
        plan.setName(request.name().trim());
        plan.setPriceMonthly(Money.of(request.priceMonthly()).amount());
        plan.setPriceYearly(Money.of(request.priceYearly()).amount());
        plan.setLimits(request.limits() == null ? new java.util.HashMap<>() : new java.util.HashMap<>(request.limits()));
        plan.setFeatures(request.features() == null ? new java.util.HashMap<>() : new java.util.HashMap<>(request.features()));
        plan.setPublicPlan(request.publicPlan() == null || request.publicPlan());
        plan.setActive(request.active() == null || request.active());
        plan.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        try {
            plans.saveAndFlush(plan);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.CODE_TAKEN, "A plan with this code already exists.");
        }
        audit.record("PLAN_CREATED", "Plan", plan.getId(), null, snapshot(plan));
        return view(plan);
    }

    public PlanView update(UUID id, UpdatePlanRequest request) {
        PlanDefinitionValidator.validate(request.limits(), request.features());
        Plan plan = find(id);
        Map<String, Object> before = snapshot(plan);
        if (request.name() != null) plan.setName(request.name().trim());
        if (request.priceMonthly() != null) plan.setPriceMonthly(Money.of(request.priceMonthly()).amount());
        if (request.priceYearly() != null) plan.setPriceYearly(Money.of(request.priceYearly()).amount());
        if (request.limits() != null) plan.setLimits(new java.util.HashMap<>(request.limits()));
        if (request.features() != null) plan.setFeatures(new java.util.HashMap<>(request.features()));
        if (request.publicPlan() != null) plan.setPublicPlan(request.publicPlan());
        if (request.active() != null) plan.setActive(request.active());
        if (request.sortOrder() != null) plan.setSortOrder(request.sortOrder());
        audit.record("PLAN_UPDATED", "Plan", id, before, snapshot(plan));
        return view(plan);
    }

    public PlanView setActive(UUID id, boolean active) {
        Plan plan = find(id);
        Map<String, Object> before = snapshot(plan);
        plan.setActive(active);
        audit.record(active ? "PLAN_ACTIVATED" : "PLAN_DEACTIVATED", "Plan", id, before, snapshot(plan));
        return view(plan);
    }

    private Plan find(UUID id) {
        return plans.findById(id).orElseThrow(NotFoundException::new);
    }

    private PlanView view(Plan plan) {
        return new PlanView(
                plan.getId(), plan.getCode(), plan.getName(), Money.of(plan.getPriceMonthly()), Money.of(plan.getPriceYearly()),
                plan.getLimits(), plan.getFeatures(), plan.isPublicPlan(), plan.isActive(), plan.getSortOrder(),
                communities.countByPlanId(plan.getId()), plan.getCreatedAt());
    }

    private static Map<String, Object> snapshot(Plan plan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", plan.getCode());
        map.put("name", plan.getName());
        map.put("priceMonthly", plan.getPriceMonthly().toPlainString());
        map.put("priceYearly", plan.getPriceYearly().toPlainString());
        map.put("limits", new LinkedHashMap<>(plan.getLimits()));
        map.put("features", new LinkedHashMap<>(plan.getFeatures()));
        map.put("publicPlan", plan.isPublicPlan());
        map.put("active", plan.isActive());
        map.put("sortOrder", plan.getSortOrder());
        return map;
    }
}

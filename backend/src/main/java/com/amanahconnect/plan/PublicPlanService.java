package com.amanahconnect.plan;

import com.amanahconnect.common.money.Money;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The plans shown on the public pricing section: public and active ones only, in display order. */
@Service
public class PublicPlanService {

    private final PlanRepository plans;

    public PublicPlanService(PlanRepository plans) {
        this.plans = plans;
    }

    @Transactional(readOnly = true)
    public List<PublicPlanView> list() {
        return plans.findByPublicPlanTrueAndActiveTrueOrderBySortOrderAsc().stream()
                // The query orders by sort order only; the code breaks ties so the order is stable.
                .sorted(Comparator.comparingInt(Plan::getSortOrder).thenComparing(Plan::getCode))
                .map(PublicPlanService::view)
                .toList();
    }

    private static PublicPlanView view(Plan plan) {
        return new PublicPlanView(
                plan.getCode(),
                plan.getName(),
                Money.of(plan.getPriceMonthly()),
                Money.of(plan.getPriceYearly()),
                // Copies, so nothing downstream can write through to the managed entity's maps.
                new LinkedHashMap<>(plan.getLimits()),
                new LinkedHashMap<>(plan.getFeatures()));
    }
}

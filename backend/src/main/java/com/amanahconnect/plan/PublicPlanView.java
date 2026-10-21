package com.amanahconnect.plan;

import com.amanahconnect.common.money.Money;
import java.util.Map;

/**
 * What the public website may know about a plan. Deliberately a subset of the admin view: no id, no
 * community count, no flags and no timestamps. Prices serialise as strings ("499.00"), and a null
 * limit means unlimited.
 */
public record PublicPlanView(
        String code,
        String name,
        Money priceMonthly,
        Money priceYearly,
        Map<String, Object> limits,
        Map<String, Object> features) {}

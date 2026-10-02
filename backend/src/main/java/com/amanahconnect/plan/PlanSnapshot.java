package com.amanahconnect.plan;

import java.util.Map;

/**
 * An immutable copy of a community's plan, safe to hold after the transaction ends. Services hand this
 * out instead of the {@link Plan} entity, whose lazy proxy cannot be used outside its session.
 */
public record PlanSnapshot(String code, String name, Map<String, Object> limits, Map<String, Object> features) {

    public PlanSnapshot {
        limits = limits == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(limits));
        features = features == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(features));
    }

    static PlanSnapshot of(Plan plan) {
        return new PlanSnapshot(plan.getCode(), plan.getName(), plan.getLimits(), plan.getFeatures());
    }
}

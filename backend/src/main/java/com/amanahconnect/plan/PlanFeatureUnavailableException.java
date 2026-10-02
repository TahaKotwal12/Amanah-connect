package com.amanahconnect.plan;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.util.List;
import java.util.Map;

/** 402 PLAN_FEATURE_UNAVAILABLE: the community's plan does not include the requested feature. */
public class PlanFeatureUnavailableException extends ApiException {

    public PlanFeatureUnavailableException(String feature, String planCode, String planName) {
        super(
                ErrorCode.PLAN_FEATURE_UNAVAILABLE,
                "The " + planName + " plan does not include " + feature.replace('_', ' ') + ". Upgrade the plan to use it.",
                List.of(),
                Map.of("feature", feature, "plan", planCode));
    }
}

package com.amanahconnect.plan;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.util.List;
import java.util.Map;

/** 402 PLAN_LIMIT_EXCEEDED. The message names the limit and the plan; the properties carry the numbers. */
public class PlanLimitExceededException extends ApiException {

    public PlanLimitExceededException(String message, String limitKey, long limit, long current, String planCode) {
        super(
                ErrorCode.PLAN_LIMIT_EXCEEDED,
                message,
                List.of(),
                Map.of("limit", limitKey, "limitValue", limit, "current", current, "plan", planCode));
    }
}

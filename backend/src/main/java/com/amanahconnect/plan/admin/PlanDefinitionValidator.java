package com.amanahconnect.plan.admin;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.plan.PlanLimitKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the free-form {@code limits} and {@code features} JSON of a plan. A typo such as
 * {@code max_member} would otherwise be stored and silently mean "unlimited", so unknown limit keys are
 * rejected, and limits must be null (unlimited) or a non-negative whole number.
 */
public final class PlanDefinitionValidator {

    static final Set<String> LIMIT_KEYS = Set.of(PlanLimitKeys.MAX_MEMBERS, PlanLimitKeys.STORAGE_MB, PlanLimitKeys.EMAILS_PER_MONTH);

    private PlanDefinitionValidator() {}

    public static void validate(Map<String, Object> limits, Map<String, Object> features) {
        List<String> problems = new ArrayList<>();
        if (limits != null) {
            limits.forEach((key, value) -> {
                if (!LIMIT_KEYS.contains(key)) {
                    problems.add("limits." + abbreviate(key) + ": unknown limit; allowed: " + String.join(", ", new java.util.TreeSet<>(LIMIT_KEYS)));
                } else if (value != null && !isNonNegativeWholeNumber(value)) {
                    problems.add("limits." + key + ": must be null (unlimited) or a whole number of 0 or more");
                }
            });
        }
        if (features != null) {
            features.forEach((key, value) -> {
                if (!key.matches("^[a-z][a-z0-9_]{0,49}$")) {
                    problems.add("features." + abbreviate(key) + ": feature names are lower_snake_case");
                } else if (!(value instanceof Boolean)) {
                    problems.add("features." + key + ": must be true or false");
                }
            });
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The plan limits or features are invalid.", problems);
        }
    }

    private static boolean isNonNegativeWholeNumber(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return ((Number) value).longValue() >= 0;
        }
        if (value instanceof Double || value instanceof Float || value instanceof java.math.BigDecimal) {
            double d = ((Number) value).doubleValue();
            return d >= 0 && d == Math.floor(d) && !Double.isInfinite(d);
        }
        return false;
    }

    private static String abbreviate(String key) {
        String clean = key.replaceAll("[^A-Za-z0-9_.-]", "?");
        return clean.length() > 40 ? clean.substring(0, 40) + "..." : clean;
    }
}

package com.amanahconnect.member;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validation of a member's free-form {@code custom_fields}: a flat object of at most 20 keys, each key lower_snake_case
 * (so it is safe in exports, URLs and queries), each value a short string, a finite number or a boolean.
 */
public final class CustomFields {

    static final int MAX_KEYS = 20;
    static final int MAX_STRING = 500;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

    private CustomFields() {}

    /** Returns a clean copy (strings trimmed, null values dropped) or throws 400 listing every problem. */
    public static Map<String, Object> validate(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return new LinkedHashMap<>();
        }
        List<String> problems = new ArrayList<>();
        Map<String, Object> clean = new LinkedHashMap<>();
        if (input.size() > MAX_KEYS) {
            problems.add("customFields: at most " + MAX_KEYS + " fields");
        }
        input.forEach((key, value) -> {
            String shown = key == null ? "null" : key.replaceAll("[^A-Za-z0-9_]", "?");
            if (key == null || !KEY.matcher(key).matches()) {
                problems.add("customFields." + (shown.length() > 40 ? shown.substring(0, 40) + "..." : shown) + ": names are lower_snake_case, up to 40 characters, starting with a letter");
            } else if (value == null) {
                // an explicit null just means "no value"
            } else if (value instanceof String s) {
                if (s.length() > MAX_STRING) problems.add("customFields." + key + ": at most " + MAX_STRING + " characters");
                else if (s.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) problems.add("customFields." + key + ": contains control characters");
                else if (!s.isBlank()) clean.put(key, s.trim());
            } else if (value instanceof Boolean) {
                clean.put(key, value);
            } else if (value instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) problems.add("customFields." + key + ": must be a finite number");
                else clean.put(key, value);
            } else {
                problems.add("customFields." + key + ": must be text, a number or true/false");
            }
        });
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", problems);
        }
        return clean;
    }
}

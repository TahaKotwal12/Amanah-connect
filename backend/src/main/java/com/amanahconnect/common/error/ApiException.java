package com.amanahconnect.common.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberate, client-visible failure with a stable {@link ErrorCode}. The message is safe to show;
 * it must never contain secrets or reveal whether an account exists.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<String> details;
    private final Map<String, Object> properties;

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, List<String> details) {
        this(code, message, details, Map.of());
    }

    /** @param properties extra, non-sensitive fields added to the problem document (e.g. the limit that was hit) */
    public ApiException(ErrorCode code, String message, List<String> details, Map<String, Object> properties) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
        this.properties = new LinkedHashMap<>(properties);
    }

    public ErrorCode code() {
        return code;
    }

    public List<String> details() {
        return details;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}

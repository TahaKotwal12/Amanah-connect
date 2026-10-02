package com.amanahconnect.common.error;

import java.util.List;

/**
 * A deliberate, client-visible failure with a stable {@link ErrorCode}. The message is safe to show;
 * it must never contain secrets or reveal whether an account exists.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<String> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, List<String> details) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public List<String> details() {
        return details;
    }
}

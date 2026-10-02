package com.amanahconnect.common.error;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes. Clients may switch on these; never rename one. */
public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "Account temporarily locked"),
    INVALID_MFA_CODE(HttpStatus.UNAUTHORIZED, "Invalid verification code"),
    MFA_SETUP_REQUIRED(HttpStatus.FORBIDDEN, "Two-factor setup required"),
    MFA_ALREADY_ENABLED(HttpStatus.CONFLICT, "Two-factor authentication is already enabled"),
    MFA_NOT_ENABLED(HttpStatus.CONFLICT, "Two-factor authentication is not enabled"),
    MFA_SETUP_NOT_STARTED(HttpStatus.CONFLICT, "Two-factor setup has not been started"),
    MFA_REQUIRED_BY_POLICY(HttpStatus.FORBIDDEN, "Two-factor authentication cannot be disabled"),
    INVALID_TOKEN(HttpStatus.BAD_REQUEST, "Invalid or expired token"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
    INVALID_CURRENT_PASSWORD(HttpStatus.FORBIDDEN, "Current password is incorrect"),
    PASSWORD_POLICY_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "Password does not meet the policy"),
    CSRF_HEADER_REQUIRED(HttpStatus.FORBIDDEN, "Required request header is missing"),
    ORIGIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "Origin not allowed"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return "urn:amanah:problem:" + name().toLowerCase().replace('_', '-');
    }
}

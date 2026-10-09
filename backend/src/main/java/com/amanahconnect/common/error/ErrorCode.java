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
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email address already in use"),
    SLUG_TAKEN(HttpStatus.CONFLICT, "Slug already in use"),
    DUPLICATE_REFERENCE(HttpStatus.CONFLICT, "Reference already recorded"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Not allowed in the current state"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "The record was changed by someone else"),
    PLAN_INACTIVE(HttpStatus.CONFLICT, "Plan is not active"),
    CODE_TAKEN(HttpStatus.CONFLICT, "Code already in use"),
    IMPORT_BATCH_CONFLICT(HttpStatus.CONFLICT, "Import batch id already used with different files"),
    IMPORT_NOT_CONFIRMABLE(HttpStatus.CONFLICT, "Import cannot be confirmed"),
    IMPORT_CONFLICT(HttpStatus.CONFLICT, "Import conflicts with existing data"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "Email address already used by another member"),
    MEMBER_HAS_UNPAID_INVOICES(HttpStatus.CONFLICT, "Member has unpaid invoices"),
    SETTING_LOCKED(HttpStatus.CONFLICT, "Setting can no longer be changed"),
    INVITE_UNAVAILABLE(HttpStatus.NOT_FOUND, "Invitation link is not valid"),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "File storage is not available"),
    OVERPAYMENT(HttpStatus.UNPROCESSABLE_ENTITY, "Payment is more than the balance due"),
    INVOICE_NOT_PAYABLE(HttpStatus.CONFLICT, "Invoice cannot take payments"),
    ALREADY_REVERSED(HttpStatus.CONFLICT, "Already reversed"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key was used for a different request"),
    FEE_PLAN_INACTIVE(HttpStatus.CONFLICT, "Fee plan is not active"),
    CATEGORY_EXISTS(HttpStatus.CONFLICT, "Category already exists"),
    LEDGER_ENTRY_READ_ONLY(HttpStatus.CONFLICT, "Ledger entry cannot be changed"),
    UPI_NOT_CONFIGURED(HttpStatus.CONFLICT, "UPI is not set up for this community"),
    MEMBER_NOT_EMAILABLE(HttpStatus.CONFLICT, "Member has no email address"),
    PAY_LINK_UNAVAILABLE(HttpStatus.NOT_FOUND, "Payment link is not valid"),
    COMPLAINT_CLOSED(HttpStatus.CONFLICT, "Complaint is closed"),
    THREAD_CLOSED(HttpStatus.CONFLICT, "Support thread is closed"),
    ANNOUNCEMENT_NOT_EDITABLE(HttpStatus.CONFLICT, "Announcement has already been sent"),
    ANNOUNCEMENT_STATE(HttpStatus.CONFLICT, "Announcement is not in a state that allows this"),
    EXPORT_IN_PROGRESS(HttpStatus.CONFLICT, "A data export is already being prepared"),
    EXPORT_LINK_UNAVAILABLE(HttpStatus.NOT_FOUND, "Download link is not valid"),
    MEMBER_ALREADY_ANONYMISED(HttpStatus.CONFLICT, "Member's personal data was already erased"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Request body too large"),
    COMMUNITY_SUSPENDED(HttpStatus.FORBIDDEN, "Community is suspended or archived"),
    PLAN_LIMIT_EXCEEDED(HttpStatus.PAYMENT_REQUIRED, "Plan limit exceeded"),
    PLAN_FEATURE_UNAVAILABLE(HttpStatus.PAYMENT_REQUIRED, "Feature not included in the plan"),
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

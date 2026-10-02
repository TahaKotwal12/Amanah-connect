package com.amanahconnect.audit;

/** Stable action names written to audit_logs.action. Never rename one: they are queried and exported. */
public final class AuditAction {

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String MFA_FAILED = "MFA_FAILED";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String RECOVERY_CODE_USED = "RECOVERY_CODE_USED";
    public static final String LOGOUT = "LOGOUT";
    public static final String LOGOUT_ALL = "LOGOUT_ALL";
    public static final String TOKEN_REUSE_DETECTED = "TOKEN_REUSE_DETECTED";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String INVITATION_CREATED = "INVITATION_CREATED";
    public static final String INVITATION_ACCEPTED = "INVITATION_ACCEPTED";
    public static final String TWO_FACTOR_SETUP_STARTED = "TWO_FACTOR_SETUP_STARTED";
    public static final String TWO_FACTOR_ENABLED = "TWO_FACTOR_ENABLED";
    public static final String TWO_FACTOR_DISABLED = "TWO_FACTOR_DISABLED";
    public static final String SUPER_ADMIN_BOOTSTRAPPED = "SUPER_ADMIN_BOOTSTRAPPED";

    private AuditAction() {}
}

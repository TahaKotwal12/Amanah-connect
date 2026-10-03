package com.amanahconnect.billing;

public enum FeeKind {
    MAINTENANCE,
    SUBSCRIPTION,
    /** Legacy: invoices imported from an old system. New fee plans and invoices use the other kinds. */
    MEMBERSHIP,
    DONATION,
    EVENT,
    FINE,
    OTHER
}

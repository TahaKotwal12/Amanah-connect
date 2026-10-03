package com.amanahconnect.plan;

/** Keys inside a plan's {@code limits} and {@code features} JSON. A missing or null limit means unlimited. */
public final class PlanLimitKeys {

    public static final String MAX_MEMBERS = "max_members";
    public static final String STORAGE_MB = "storage_mb";
    public static final String EMAILS_PER_MONTH = "emails_per_month";
    /** Optional: a cap per India calendar day on top of the monthly one (a burst guard). */
    public static final String EMAILS_PER_DAY = "emails_per_day";

    public static final String FEATURE_PDF_REPORTS = "pdf_reports";
    public static final String FEATURE_UPI_QR = "upi_qr";
    public static final String FEATURE_CSV_EXPORT = "csv_export";
    public static final String FEATURE_RECEIPTS_EMAIL = "receipts_email";

    private PlanLimitKeys() {}
}

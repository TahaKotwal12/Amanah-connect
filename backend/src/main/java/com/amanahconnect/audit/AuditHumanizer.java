package com.amanahconnect.audit;

import com.amanahconnect.mail.MailFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns an audit record into a sentence a community admin can read ("Recorded a payment of ₹1,000.00 on invoice INV-2026-27/000123").
 * Only ids, amounts, numbers and statuses are used (the audit log holds no names or addresses of members), and an action
 * nobody has written a sentence for still reads sensibly ("Fee plan updated").
 */
public final class AuditHumanizer {

    /** Bookkeeping that is noise on a dashboard: reads, uploads asked for, sign-ins, and security events (those live in the audit trail). */
    public static final Set<String> NOT_DASHBOARD_WORTHY = Set.of(
            "SUPPORT_THREAD_READ", "NOTIFICATION_READ", "NOTIFICATIONS_READ_ALL", "LEDGER_ATTACHMENT_UPLOAD_REQUESTED", "SUPPORT_ATTACHMENT_UPLOAD_REQUESTED",
            "COMMUNITY_LOGO_UPLOAD_REQUESTED", "ANNOUNCEMENT_TEST_SENT", "LOGIN_SUCCESS", "LOGIN_FAILED", "LOGOUT", "LOGOUT_ALL", "MFA_FAILED", "TOKEN_REUSE_DETECTED",
            "ACCOUNT_LOCKED", "RECOVERY_CODE_USED", "SUPPORT_VIEW", "PASSWORD_RESET_REQUESTED", "PAY_LINK_CREATED", "TWO_FACTOR_SETUP_STARTED");

    private AuditHumanizer() {}

    public static String describe(String action, Map<String, Object> before, Map<String, Object> after, String currency) {
        Map<String, Object> a = after == null ? Map.of() : after;
        Map<String, Object> b = before == null ? Map.of() : before;
        return switch (action) {
            case "PAYMENT_RECORDED" -> "Recorded a payment of " + money(a, "amount", currency) + onInvoice(a) + receipt(a);
            case "PAYMENT_REVERSED" -> "Reversed a payment of " + money(a, "amount", currency) + onInvoice(a) + reason(a);
            case "DONATION_RECORDED" -> "Recorded a donation of " + money(a, "amount", currency) + receipt(a);
            case "INVOICE_CREATED" -> "Created invoice" + number(a, "invoiceNo") + amountPart(a, currency);
            case "INVOICE_ISSUED" -> "Issued invoice" + number(a, "invoiceNo");
            case "INVOICE_UPDATED" -> "Edited invoice" + number(a, "invoiceNo");
            case "INVOICE_CANCELLED" -> "Cancelled invoice" + number(a, "invoiceNo") + reason(a);
            case "INVOICE_BILL_RESENT" -> "Re-sent the bill for invoice" + number(a, "invoiceNo");
            case "INVOICES_GENERATED" -> "Generated invoices" + forPeriod(a) + countPart(a, "created", "created");
            case "INVOICES_MARKED_OVERDUE" -> "Marked " + count(a, "count") + " invoice(s) overdue";
            case "RECEIPT_RESENT" -> "Re-sent receipt" + number(a, "receiptNo");
            case "FEE_PLAN_CREATED" -> "Created fee plan" + named(a, "name");
            case "FEE_PLAN_UPDATED" -> "Updated fee plan" + named(a, "name");
            case "LEDGER_ENTRY_CREATED" -> "Added a ledger entry" + amountPart(a, currency);
            case "LEDGER_ENTRY_UPDATED" -> "Edited a ledger entry";
            case "LEDGER_ENTRY_REVERSED" -> "Reversed a ledger entry" + reason(a);
            case "LEDGER_CATEGORY_CREATED" -> "Added a ledger category" + named(a, "name");
            case "LEDGER_CATEGORY_UPDATED" -> "Changed a ledger category";
            case "MEMBER_CREATED" -> "Added member" + number(a, "memberNo");
            case "MEMBER_UPDATED" -> "Updated member" + number(a, "memberNo");
            case "MEMBER_DELETED" -> "Deleted a member" + (Boolean.TRUE.equals(a.get("forced")) ? " (with unpaid invoices)" : "");
            case "MEMBER_ANONYMISED" -> "Erased a member's personal data";
            case "MEMBER_REGISTRATION_RECEIVED" -> "A registration request came in";
            case "MEMBER_REGISTRATION_APPROVED" -> "Approved a member registration";
            case "MEMBER_REGISTRATION_REJECTED" -> "Rejected a member registration";
            case "MEMBER_INVITE_CREATED" -> "Created a member invite link";
            case "MEMBER_INVITE_EMAILED" -> "Emailed a member invite";
            case "MEMBER_INVITE_REVOKED" -> "Revoked a member invite link";
            case "MEMBERS_EXPORTED" -> "Exported the member list";
            case "COMMUNITY_IMPORT_DRY_RUN" -> "Checked a member import file";
            case "COMMUNITY_IMPORT_CONFIRMED" -> "Imported members";
            case "COMPLAINT_CREATED" -> "Logged a complaint";
            case "COMPLAINT_UPDATED" -> "Edited a complaint";
            case "COMPLAINT_STATUS_CHANGED" -> "Changed a complaint from " + status(b) + " to " + status(a);
            case "COMPLAINT_COMMENT_ADDED" -> "Commented on a complaint" + ("INTERNAL".equals(a.get("visibility")) ? " (internal note)" : "");
            case "ANNOUNCEMENT_CREATED" -> "Created an announcement";
            case "ANNOUNCEMENT_UPDATED" -> "Edited an announcement";
            case "ANNOUNCEMENT_SCHEDULED" -> "Scheduled an announcement";
            case "ANNOUNCEMENT_UNSCHEDULED" -> "Took an announcement off the schedule";
            case "ANNOUNCEMENT_SENT" -> "Sent an announcement";
            case "SUPPORT_THREAD_CREATED" -> "Contacted support";
            case "SUPPORT_MESSAGE_POSTED" -> "Sent a support message";
            case "SUPPORT_THREAD_UPDATED" -> "Support updated a conversation";
            case "COMMUNITY_SETTINGS_UPDATED" -> "Changed the community settings";
            case "COMMUNITY_LOGO_REMOVED" -> "Removed the community logo";
            case "COMMUNITY_UPDATED" -> "Community details were updated";
            case "COMMUNITY_CREATED" -> "The community was created";
            case "COMMUNITY_SUSPENDED" -> "The community was suspended";
            case "COMMUNITY_ACTIVATED" -> "The community was activated";
            case "COMMUNITY_EXPORTED" -> "The community's details were exported";
            case "REPORT_EXPORTED" -> "Downloaded the " + String.valueOf(a.getOrDefault("report", "")).replace('-', ' ') + " report";
            case "DATA_EXPORT_REQUESTED" -> "Asked for a download of all the community's data";
            case "DATA_EXPORT_COMPLETED" -> "The data download was prepared";
            case "DATA_EXPORT_FAILED" -> "The data download could not be prepared";
            case "DATA_EXPORT_DOWNLOADED" -> "The data download was opened";
            case "AUDIT_EXPORTED" -> "Downloaded the audit trail";
            case "SUBSCRIPTION_RECORDED" -> "A subscription payment was recorded";
            case "SUBSCRIPTION_EXPIRED" -> "The subscription expired";
            case "INVITATION_CREATED" -> "An admin was invited";
            case "INVITATION_ACCEPTED" -> "An admin accepted an invitation";
            case "PASSWORD_CHANGED" -> "An admin changed their password";
            case "TWO_FACTOR_ENABLED" -> "An admin turned on two-factor authentication";
            case "TWO_FACTOR_DISABLED" -> "An admin turned off two-factor authentication";
            default -> fallback(action);
        };
    }

    /** {@code FEE_PLAN_UPDATED} as {@code Fee plan updated}. */
    static String fallback(String action) {
        String words = action.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        return words.isEmpty() ? action : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String money(Map<String, Object> m, String key, String currency) {
        Object v = m.get(key);
        return v == null ? "an amount" : MailFormat.money(v, currency);
    }

    private static String amountPart(Map<String, Object> m, String currency) {
        return m.get("amount") == null ? "" : " of " + MailFormat.money(m.get("amount"), currency);
    }

    private static String onInvoice(Map<String, Object> m) {
        return m.get("invoiceNo") == null ? "" : " on invoice " + m.get("invoiceNo");
    }

    private static String receipt(Map<String, Object> m) {
        return m.get("receiptNo") == null ? "" : " (receipt " + m.get("receiptNo") + ")";
    }

    private static String number(Map<String, Object> m, String key) {
        return m.get(key) == null ? "" : " " + m.get(key);
    }

    private static String named(Map<String, Object> m, String key) {
        return m.get(key) == null ? "" : " \"" + m.get(key) + "\"";
    }

    private static String reason(Map<String, Object> m) {
        return m.get("reason") == null || String.valueOf(m.get("reason")).isBlank() ? "" : ": " + m.get("reason");
    }

    private static String forPeriod(Map<String, Object> m) {
        return m.get("period") == null ? "" : " for " + m.get("period");
    }

    private static String countPart(Map<String, Object> m, String key, String word) {
        return m.get(key) == null ? "" : " (" + m.get(key) + " " + word + ")";
    }

    private static String count(Map<String, Object> m, String key) {
        return m.get(key) == null ? "some" : String.valueOf(m.get(key));
    }

    private static String status(Map<String, Object> m) {
        return m.get("status") == null ? "?" : String.valueOf(m.get("status")).toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
